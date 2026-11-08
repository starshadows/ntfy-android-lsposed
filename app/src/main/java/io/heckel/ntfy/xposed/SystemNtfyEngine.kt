package io.heckel.ntfy.xposed

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.drawable.Icon
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.net.ConnectivityManager
import android.net.Network
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Base64
import com.google.gson.Gson
import com.google.gson.JsonParser
import io.heckel.ntfy.util.Log
import io.heckel.ntfy.util.toEmojis
import kotlinx.coroutines.*
import okhttp3.*
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class SystemNtfyEngine private constructor(private val context: Context) {
    companion object {
        const val TAG = "SystemNtfyEngine"
        private const val PREFS_NAME = "systemui_ntfy_prefs"
        private const val KEY_PAYLOAD = "cached_payload"
        private const val NTFY_BRAND_COLOR = 0xFF338574.toInt() // md_theme_primary green

        @Volatile
        private var instance: SystemNtfyEngine? = null

        fun getInstance(context: Context): SystemNtfyEngine {
            return instance ?: synchronized(this) {
                instance ?: SystemNtfyEngine(context.applicationContext ?: context).also { instance = it }
            }
        }
    }

    private enum class ConnectionStatus {
        DISCONNECTED,
        CONNECTING,
        CONNECTED
    }

    private data class ManagedConnection(
        val ws: WebSocket,
        val topics: String,
        @Volatile var status: ConnectionStatus
    )

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val connections = ConcurrentHashMap<String, ManagedConnection>()
    private val retryJobs = ConcurrentHashMap<String, Job>()
    private val errorCounts = ConcurrentHashMap<String, Int>()
    private val isStarted = AtomicBoolean(false)

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    private var currentPayload: NtfyBridge.SyncPayload? = null
    private var networkDebounceJob: Job? = null

    private val postedNotificationIds = ConcurrentHashMap.newKeySet<Int>()
    private val topicToNotificationIds = ConcurrentHashMap<String, MutableSet<Int>>()
    private val activeTopic = java.util.concurrent.atomic.AtomicReference<String?>(null)

    private val priorityVibrateMap = ConcurrentHashMap<Int, Boolean>().apply {
        put(5, true)  // Priority 5 (Max) -> Vibrate
        put(4, false) // Priority 4 (High) -> No vibrate (matches user settings)
        put(3, false) // Priority 3 (Default) -> No vibrate
        put(2, false) // Priority 2 (Low) -> Silent
        put(1, false) // Priority 1 (Min) -> Silent
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .pingInterval(30, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(true)
        .build()

    private val ipcReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context?, intent: Intent?) {
            when (intent?.action) {
                NtfyBridge.ACTION_SYNC_SUBSCRIPTIONS -> {
                    val json = intent.getStringExtra(NtfyBridge.EXTRA_PAYLOAD_JSON) ?: return
                    Log.d(TAG, "Received updated subscriptions from App: $json")
                    try {
                        val payload = gson.fromJson(json, NtfyBridge.SyncPayload::class.java)
                        payload.priorityVibration?.forEach { (priority, vibrate) ->
                            priorityVibrateMap[priority] = vibrate
                            Log.d(TAG, "Updated priority $priority vibration rule to $vibrate from App sync")
                        }
                        savePayload(payload)
                        refreshConnections(payload)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to parse updated subscriptions", e)
                    }
                }
                NtfyBridge.ACTION_QUERY_LSP_STATUS -> {
                    sendLspStatusReply()
                }
                NtfyBridge.ACTION_CLEAR_SYSTEM_NOTIFICATIONS -> {
                    val topic = intent.getStringExtra(NtfyBridge.EXTRA_TOPIC)
                    if (!topic.isNullOrBlank()) {
                        val ids = topicToNotificationIds.remove(topic)
                        ids?.forEach { notifId ->
                            notificationManager.cancel(notifId)
                            postedNotificationIds.remove(notifId)
                        }
                        Log.d(TAG, "Cleared SystemUI notifications for topic: $topic (${ids?.size ?: 0} removed)")
                    } else {
                        postedNotificationIds.forEach { notifId ->
                            notificationManager.cancel(notifId)
                        }
                        postedNotificationIds.clear()
                        topicToNotificationIds.clear()
                        Log.d(TAG, "Cleared all SystemUI notifications on app open")
                    }
                }
                NtfyBridge.ACTION_TOPIC_ACTIVE -> {
                    val topic = intent.getStringExtra(NtfyBridge.EXTRA_TOPIC)
                    activeTopic.set(topic)
                    Log.d(TAG, "Active topic updated in SystemUI: $topic")
                    if (!topic.isNullOrBlank()) {
                        val ids = topicToNotificationIds.remove(topic)
                        ids?.forEach { notifId ->
                            notificationManager.cancel(notifId)
                            postedNotificationIds.remove(notifId)
                        }
                    }
                }
            }
        }
    }

    private fun sendLspStatusReply() {
        val reply = Intent(NtfyBridge.ACTION_REPLY_LSP_STATUS).apply {
            setPackage(NtfyBridge.NTFY_PACKAGE)
            putExtra(NtfyBridge.EXTRA_LSP_ACTIVE, true)
        }
        context.sendBroadcast(reply)
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            networkDebounceJob?.cancel()
            networkDebounceJob = scope.launch {
                delay(2000) // Debounce network transitions to prevent duplicate reconnect bursts
                Log.d(TAG, "Network stabilized, checking connections...")
                currentPayload?.let { refreshConnections(it) }
            }
        }
    }

    fun start() {
        if (!isStarted.compareAndSet(false, true)) return
        Log.i(TAG, "Starting SystemNtfyEngine in SystemUI process")

        // 1. Register IPC receiver
        val filter = IntentFilter().apply {
            addAction(NtfyBridge.ACTION_SYNC_SUBSCRIPTIONS)
            addAction(NtfyBridge.ACTION_QUERY_LSP_STATUS)
            addAction(NtfyBridge.ACTION_CLEAR_SYSTEM_NOTIFICATIONS)
            addAction(NtfyBridge.ACTION_TOPIC_ACTIVE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.registerReceiver(ipcReceiver, filter, Context.RECEIVER_EXPORTED)
        } else {
            context.registerReceiver(ipcReceiver, filter)
        }

        // 2. Register network monitor
        try {
            connectivityManager.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to register network callback: ${e.message}")
        }

        // 3. Inform app that LSPosed is active
        sendLspStatusReply()

        // 4. Clean up any old SystemUI-level test channels
        try {
            (1..5).forEach { p ->
                notificationManager.deleteNotificationChannel("ntfy_channel_v3_$p")
                notificationManager.deleteNotificationChannel("ntfy_channel_v2_$p")
                notificationManager.deleteNotificationChannel("ntfy_system_channel_$p")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to cleanup old SystemUI channels: ${e.message}")
        }

        // 5. Load cached subscriptions and connect
        val cached = loadPayload()
        if (cached != null && cached.subscriptions.isNotEmpty()) {
            Log.i(TAG, "Loaded ${cached.subscriptions.size} cached subscriptions on boot, connecting...")
            refreshConnections(cached)
        } else {
            Log.i(TAG, "No cached subscriptions found yet, requesting sync from app...")
            val req = Intent(NtfyBridge.ACTION_REQUEST_SYNC_SUBSCRIPTIONS).apply {
                setPackage(NtfyBridge.NTFY_PACKAGE)
            }
            context.sendBroadcast(req)
        }
    }

    private fun savePayload(payload: NtfyBridge.SyncPayload) {
        currentPayload = payload
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_PAYLOAD, gson.toJson(payload)).apply()
    }

    private fun loadPayload(): NtfyBridge.SyncPayload? {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val json = prefs.getString(KEY_PAYLOAD, null) ?: return null
        return try {
            gson.fromJson(json, NtfyBridge.SyncPayload::class.java).also { currentPayload = it }
        } catch (e: Exception) {
            null
        }
    }

    @Synchronized
    private fun refreshConnections(payload: NtfyBridge.SyncPayload) {
        scope.launch {
            val grouped = payload.subscriptions.groupBy { it.baseUrl }
            val desiredUrls = grouped.keys

            // Close connections that are no longer in subscription config
            connections.keys.toList().forEach { url ->
                if (!desiredUrls.contains(url)) {
                    Log.d(TAG, "Closing connection for $url (removed from config)")
                    retryJobs.remove(url)?.cancel()
                    val conn = connections.remove(url)
                    try {
                        conn?.ws?.close(1000, "Removed")
                    } catch (_: Exception) {}
                }
            }

            // Connect to desired baseUrls
            grouped.forEach { (baseUrl, subs) ->
                val topics = subs.map { it.topic }.distinct().filter { it.isNotBlank() }.joinToString(",")
                if (topics.isEmpty()) {
                    Log.d(TAG, "No valid topics for $baseUrl, skipping")
                    return@forEach
                }

                val existing = connections[baseUrl]
                if (existing != null && existing.topics == topics &&
                    (existing.status == ConnectionStatus.CONNECTED || existing.status == ConnectionStatus.CONNECTING)) {
                    Log.d(TAG, "Connection for $baseUrl [$topics] already in state ${existing.status}, skipping")
                    return@forEach
                }

                val user = payload.users.firstOrNull { it.baseUrl == baseUrl }
                startConnection(baseUrl, topics, user, subs)
            }
        }
    }

    private fun startConnection(
        baseUrl: String,
        topics: String,
        user: NtfyBridge.SyncUser?,
        subs: List<NtfyBridge.SyncSubscription>
    ) {
        retryJobs[baseUrl]?.cancel()

        // Clean up previous connection if any
        val oldConn = connections.remove(baseUrl)
        if (oldConn != null) {
            try {
                oldConn.ws.close(1000, "Reconnecting")
            } catch (_: Exception) {}
        }

        val wsUrl = "${baseUrl.replaceFirst("^http".toRegex(), "ws").trimEnd('/')}/${topics}/ws?since=none"
        Log.i(TAG, "Opening WebSocket connection: $wsUrl")

        val reqBuilder = Request.Builder().url(wsUrl)
        if (user != null && user.username.isNotEmpty()) {
            val credentials = "${user.username}:${user.password}"
            val encoded = Base64.encodeToString(credentials.toByteArray(StandardCharsets.UTF_8), Base64.NO_WRAP)
            reqBuilder.header("Authorization", "Basic $encoded")
        }

        val request = reqBuilder.build()
        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.i(TAG, "WebSocket connected successfully: $wsUrl")
                connections[baseUrl]?.let { it.status = ConnectionStatus.CONNECTED }
                errorCounts[baseUrl] = 0
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Received message from $baseUrl: $text")
                handleIncomingMessage(baseUrl, text, subs)
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code ?: 0
                val msg = response?.message ?: t.message
                Log.w(TAG, "WebSocket failure for $baseUrl (HTTP $code): $msg")
                connections.remove(baseUrl)

                val currentErr = (errorCounts[baseUrl] ?: 0) + 1
                errorCounts[baseUrl] = currentErr

                val retrySeconds = if (code == 429) {
                    Log.w(TAG, "Hit 429 Too Many Requests from $baseUrl; applying safety cooldown of 60 seconds")
                    60L
                } else {
                    val backoffs = listOf(5L, 10L, 20L, 30L, 60L, 120L)
                    backoffs.getOrElse(currentErr - 1) { 120L }
                }

                scheduleReconnect(baseUrl, topics, user, subs, retrySeconds)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                Log.i(TAG, "WebSocket closed for $baseUrl: code=$code, reason=$reason")
                connections.remove(baseUrl)
                if (code != 1000) {
                    val currentErr = (errorCounts[baseUrl] ?: 0) + 1
                    errorCounts[baseUrl] = currentErr
                    scheduleReconnect(baseUrl, topics, user, subs, 10L)
                }
            }
        }

        val ws = okHttpClient.newWebSocket(request, listener)
        connections[baseUrl] = ManagedConnection(ws, topics, ConnectionStatus.CONNECTING)
    }

    private fun scheduleReconnect(
        baseUrl: String,
        topics: String,
        user: NtfyBridge.SyncUser?,
        subs: List<NtfyBridge.SyncSubscription>,
        delaySeconds: Long
    ) {
        retryJobs[baseUrl]?.cancel()
        retryJobs[baseUrl] = scope.launch {
            Log.d(TAG, "Scheduling reconnect for $baseUrl in $delaySeconds seconds")
            delay(delaySeconds * 1000)
            startConnection(baseUrl, topics, user, subs)
        }
    }

    private fun handleIncomingMessage(
        baseUrl: String,
        jsonText: String,
        subs: List<NtfyBridge.SyncSubscription>
    ) {
        try {
            val json = JsonParser.parseString(jsonText).asJsonObject
            val event = json.get("event")?.asString ?: return
            if (event != "message") return

            val topic = json.get("topic")?.asString ?: ""
            val subscription = subs.firstOrNull { it.topic == topic }

            val id = json.get("id")?.asString ?: System.currentTimeMillis().toString()
            val shortBase = baseUrl.replace("https://", "").replace("http://", "").trimEnd('/')
            val defaultDisplayName = if (shortBase == "ntfy.sh") topic else "$shortBase/$topic"
            val displayName = subscription?.displayName ?: defaultDisplayName

            val rawTitle = json.get("title")?.asString ?: displayName
            val message = json.get("message")?.asString ?: ""
            val priority = json.get("priority")?.asInt ?: 3
            val click = json.get("click")?.asString

            // Parse tags and format emojis matching foreground UI
            val tagsList = mutableListOf<String>()
            json.get("tags")?.asJsonArray?.forEach {
                val tagStr = it.asString
                if (tagStr.isNotBlank()) tagsList.add(tagStr)
            }
            val emojis = toEmojis(tagsList)
            val formattedTitle = if (emojis.isNotEmpty()) {
                emojis.joinToString("") + " " + rawTitle
            } else {
                rawTitle
            }

            // Check priority filter
            val minPriority = subscription?.minPriority ?: 1
            if (priority < minPriority) {
                Log.d(TAG, "Message priority $priority is lower than minPriority $minPriority, skipping notification")
                return
            }

            // Check muted
            val mutedUntil = subscription?.mutedUntil ?: 0L
            if (mutedUntil > System.currentTimeMillis() / 1000) {
                Log.d(TAG, "Topic $topic is muted, skipping notification")
                return
            }

            // 1. Post notification via SystemUI only if user is not currently viewing this exact topic in DetailActivity!
            val isViewingThisTopic = (activeTopic.get() == topic)
            if (isViewingThisTopic) {
                Log.d(TAG, "User is currently viewing topic $topic in app, skipping status bar popup")
            } else {
                displayNotification(id, topic, formattedTitle, message, priority, click, baseUrl, subscription, displayName)
            }

            // 2. Forward to app receiver so DB history is saved and open UI is refreshed
            NtfyBridge.dispatchNotificationToApp(context, jsonText, baseUrl)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to handle incoming message", e)
        }
    }

    private fun displayNotification(
        id: String,
        topic: String,
        title: String,
        message: String,
        priority: Int,
        click: String?,
        baseUrl: String,
        subscription: NtfyBridge.SyncSubscription?,
        displayName: String
    ) {
        try {
            // Respect the user's notification channel settings:
            // Priority 5: Vibrate (unless user turned off)
            // Priority 4, 3, 2, 1: No vibrate by default (matches user's screenshot where High has no vibration)
            val shouldVibrate = priorityVibrateMap[priority] ?: (priority == 5)
            val pause = 300L
            val vibrationPattern = if (shouldVibrate) {
                when (priority) {
                    5 -> longArrayOf(pause, 100, pause, 100, pause, 100, pause, 2000, pause, 100, pause, 100, pause, 100, pause, 2000)
                    4 -> longArrayOf(pause, 100, pause, 100, pause, 100, pause, 2000)
                    else -> longArrayOf(0, 250, 250, 250)
                }
            } else {
                null
            }

            // Channel importance matching Android & user settings:
            // Priority 5 (Max): High importance, banner, lockscreen, vibration
            // Priority 4 (High): High importance, banner, lockscreen, NO vibration (matching user screenshot)
            // Priority 3 (Default): Default importance, lockscreen, NO banner, NO vibration
            // Priority 2 (Low): Low importance, silent
            // Priority 1 (Min): Min importance, silent
            val importance = when (priority) {
                5 -> NotificationManager.IMPORTANCE_HIGH
                4 -> NotificationManager.IMPORTANCE_HIGH
                3 -> NotificationManager.IMPORTANCE_DEFAULT
                2 -> NotificationManager.IMPORTANCE_LOW
                else -> NotificationManager.IMPORTANCE_MIN
            }

            val channelId = "ntfy_sys_p${priority}_v${if (shouldVibrate) 1 else 0}"
            val channelName = when (priority) {
                5 -> "最高优先级"
                4 -> "高优先级"
                3 -> "默认优先级"
                2 -> "低优先级"
                else -> "最低优先级"
            }

            val channel = NotificationChannel(channelId, channelName, importance).apply {
                setShowBadge(true)
                if (shouldVibrate && vibrationPattern != null) {
                    enableVibration(true)
                    this.vibrationPattern = vibrationPattern
                } else {
                    enableVibration(false)
                    this.vibrationPattern = null
                }

                if (priority >= 4) {
                    enableLights(true)
                    lightColor = NTFY_BRAND_COLOR
                    val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                    val audioAttributes = AudioAttributes.Builder()
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                        .build()
                    setSound(soundUri, audioAttributes)
                } else if (priority <= 2) {
                    setSound(null, null)
                }
                if (priority == 5) {
                    setBypassDnd(true)
                }
            }
            notificationManager.createNotificationChannel(channel)

            // Resolve small icon from ntfy app package
            val icon: Icon = try {
                val ntfyPkgContext = context.createPackageContext(NtfyBridge.NTFY_PACKAGE, Context.CONTEXT_IGNORE_SECURITY)
                val resId = ntfyPkgContext.resources.getIdentifier("ic_notification", "drawable", NtfyBridge.NTFY_PACKAGE)
                if (resId != 0) {
                    Icon.createWithResource(NtfyBridge.NTFY_PACKAGE, resId)
                } else {
                    Icon.createWithResource(context, android.R.drawable.stat_notify_chat)
                }
            } catch (e: Exception) {
                Icon.createWithResource(context, android.R.drawable.stat_notify_chat)
            }

            val subId = subscription?.id ?: 0L
            val instant = subscription?.instant ?: true
            val mutedUntil = subscription?.mutedUntil ?: 0L

            val appIntent = Intent().apply {
                setClassName(NtfyBridge.NTFY_PACKAGE, "io.heckel.ntfy.ui.DetailActivity")
                putExtra("subscriptionId", subId)
                putExtra("subscriptionBaseUrl", baseUrl)
                putExtra("subscriptionTopic", topic)
                putExtra("subscriptionDisplayName", displayName)
                putExtra("subscriptionInstant", instant)
                putExtra("subscriptionMutedUntil", mutedUntil)
                putExtra("baseUrl", baseUrl)
                putExtra("topic", topic)
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }

            val pendingIntent = if (!click.isNullOrBlank()) {
                try {
                    val uri = android.net.Uri.parse(click)
                    val viewIntent = Intent(Intent.ACTION_VIEW, uri).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    PendingIntent.getActivity(
                        context,
                        id.hashCode(),
                        viewIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                } catch (_: Exception) {
                    PendingIntent.getActivity(
                        context,
                        id.hashCode(),
                        appIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                    )
                }
            } else {
                PendingIntent.getActivity(
                    context,
                    id.hashCode(),
                    appIntent,
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
                )
            }

            val notificationId = (id.hashCode() and 0x7FFFFFFF)
            postedNotificationIds.add(notificationId)
            topicToNotificationIds.computeIfAbsent(topic) { ConcurrentHashMap.newKeySet() }.add(notificationId)

            val notifBuilder = Notification.Builder(context, channelId)
                .setSmallIcon(icon)
                .setColor(NTFY_BRAND_COLOR) // Exact foreground green theme color
                .setContentTitle(title)
                .setContentText(message)
                .setStyle(Notification.BigTextStyle().bigText(message))
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)

            if (priority >= 4) {
                notifBuilder.setPriority(Notification.PRIORITY_HIGH)
            } else if (priority == 3) {
                notifBuilder.setPriority(Notification.PRIORITY_DEFAULT)
            } else {
                notifBuilder.setPriority(Notification.PRIORITY_LOW)
            }

            // Trigger hardware vibrator ONLY when shouldVibrate is true
            if (shouldVibrate && vibrationPattern != null) {
                try {
                    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                        vm?.defaultVibrator ?: (context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator)
                    } else {
                        context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        vibrator?.vibrate(VibrationEffect.createWaveform(vibrationPattern, -1))
                    } else {
                        vibrator?.vibrate(vibrationPattern, -1)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to trigger vibrator: ${e.message}")
                }
            }

            notificationManager.notify(notificationId, notifBuilder.build())
            Log.i(TAG, "Displayed notification #$notificationId (Priority=$priority, Vibrate=$shouldVibrate): [$title] $message")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to display notification", e)
        }
    }
}
