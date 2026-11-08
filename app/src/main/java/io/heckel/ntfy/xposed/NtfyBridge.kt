package io.heckel.ntfy.xposed

import android.content.Context
import android.content.Intent
import com.google.gson.Gson
import io.heckel.ntfy.db.Repository
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

object NtfyBridge {
    const val TAG = "NtfyBridge"

    // SystemUI Package Name
    const val SYSTEM_UI_PACKAGE = "com.android.systemui"
    const val NTFY_PACKAGE = "io.heckel.ntfy"

    // Broadcast Actions
    const val ACTION_SYNC_SUBSCRIPTIONS = "io.heckel.ntfy.ACTION_SYNC_SUBSCRIPTIONS"
    const val ACTION_SYSTEM_NOTIFICATION_ARRIVED = "io.heckel.ntfy.ACTION_SYSTEM_NOTIFICATION_ARRIVED"
    const val ACTION_REQUEST_SYNC_SUBSCRIPTIONS = "io.heckel.ntfy.ACTION_REQUEST_SYNC_SUBSCRIPTIONS"
    const val ACTION_QUERY_LSP_STATUS = "io.heckel.ntfy.ACTION_QUERY_LSP_STATUS"
    const val ACTION_REPLY_LSP_STATUS = "io.heckel.ntfy.ACTION_REPLY_LSP_STATUS"
    const val ACTION_CLEAR_SYSTEM_NOTIFICATIONS = "io.heckel.ntfy.ACTION_CLEAR_SYSTEM_NOTIFICATIONS"
    const val ACTION_TOPIC_ACTIVE = "io.heckel.ntfy.ACTION_TOPIC_ACTIVE"

    // Extras
    const val EXTRA_PAYLOAD_JSON = "extra_payload_json"
    const val EXTRA_NOTIFICATION_JSON = "extra_notification_json"
    const val EXTRA_BASE_URL = "extra_base_url"
    const val EXTRA_LSP_ACTIVE = "extra_lsp_active"
    const val EXTRA_TOPIC = "extra_topic"

    @Volatile
    var isXposedHookActive: Boolean = false

    @Volatile
    var isSystemUIEngineRunning: Boolean = false

    private val gson = Gson()

    data class SyncSubscription(
        val id: Long,
        val baseUrl: String,
        val topic: String,
        val instant: Boolean,
        val minPriority: Int,
        val mutedUntil: Long,
        val displayName: String?
    )

    data class SyncUser(
        val baseUrl: String,
        val username: String,
        val password: String
    )

    data class SyncPayload(
        val subscriptions: List<SyncSubscription>,
        val users: List<SyncUser>,
        val priorityVibration: Map<Int, Boolean>? = null,
        val timestamp: Long = System.currentTimeMillis()
    )

    /**
     * Called from ntfy app whenever subscriptions or users change.
     * Broadcasts the configuration directly to com.android.systemui.
     */
    fun syncToSystemUI(context: Context, repository: Repository) {
        GlobalScope.launch(Dispatchers.IO) {
            try {
                val subs = repository.getSubscriptions().map {
                    SyncSubscription(
                        id = it.id,
                        baseUrl = it.baseUrl,
                        topic = it.topic,
                        instant = it.instant,
                        minPriority = it.minPriority,
                        mutedUntil = it.mutedUntil,
                        displayName = it.displayName
                    )
                }

                val users = repository.getUsers().map {
                    SyncUser(
                        baseUrl = it.baseUrl,
                        username = it.username,
                        password = it.password
                    )
                }

                // Query notification channels configured in io.heckel.ntfy
                val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager
                val channels = nm?.notificationChannels ?: emptyList()
                val vibrateMap = mutableMapOf<Int, Boolean>()

                val maxChan = channels.firstOrNull { it.id.endsWith("-max") }
                vibrateMap[5] = maxChan?.shouldVibrate() ?: true

                val highChan = channels.firstOrNull { it.id.endsWith("-high") }
                vibrateMap[4] = highChan?.shouldVibrate() ?: false // Default false matching user setting

                val defChan = channels.firstOrNull { it.id == "ntfy" || it.id.matches(Regex("ntfy-subscription-\\d+")) }
                vibrateMap[3] = defChan?.shouldVibrate() ?: false

                vibrateMap[2] = false
                vibrateMap[1] = false

                val payload = SyncPayload(subscriptions = subs, users = users, priorityVibration = vibrateMap)
                val json = gson.toJson(payload)

                val intent = Intent(ACTION_SYNC_SUBSCRIPTIONS).apply {
                    setPackage(SYSTEM_UI_PACKAGE)
                    putExtra(EXTRA_PAYLOAD_JSON, json)
                }
                context.sendBroadcast(intent)
                Log.d(TAG, "Synced ${subs.size} subscriptions and vibration rules to SystemUI: $vibrateMap")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to sync subscriptions to SystemUI", e)
            }
        }
    }

    /**
     * Send notification payload back to ntfy app so message history is updated
     * and native notification is posted using the app's official channels.
     */
    fun dispatchNotificationToApp(context: Context, notificationJson: String, baseUrl: String = "") {
        try {
            val intent = Intent(ACTION_SYSTEM_NOTIFICATION_ARRIVED).apply {
                component = android.content.ComponentName(NTFY_PACKAGE, "io.heckel.ntfy.xposed.NtfySyncReceiver")
                putExtra(EXTRA_NOTIFICATION_JSON, notificationJson)
                putExtra(EXTRA_BASE_URL, baseUrl)
                addFlags(Intent.FLAG_INCLUDE_STOPPED_PACKAGES)
                addFlags(Intent.FLAG_RECEIVER_FOREGROUND)
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Dispatched notification to app receiver with FOREGROUND flag")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to dispatch notification to app", e)
        }
    }

    /**
     * Ask SystemUI to cancel any notifications it posted.
     * If topic is specified, only cancel notifications for that topic.
     * If topic is null, cancel all ntfy notifications.
     */
    fun clearSystemNotifications(context: Context, topic: String? = null) {
        try {
            val intent = Intent(ACTION_CLEAR_SYSTEM_NOTIFICATIONS).apply {
                setPackage(SYSTEM_UI_PACKAGE)
                if (topic != null) {
                    putExtra(EXTRA_TOPIC, topic)
                }
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Requested SystemUI to clear notifications (topic=$topic)")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send clearSystemNotifications", e)
        }
    }

    /**
     * Inform SystemUI which topic is currently open in DetailActivity.
     * When a topic is active, SystemUI suppresses status bar popups for it.
     */
    fun notifyTopicActive(context: Context, topic: String?) {
        try {
            val intent = Intent(ACTION_TOPIC_ACTIVE).apply {
                setPackage(SYSTEM_UI_PACKAGE)
                if (topic != null) {
                    putExtra(EXTRA_TOPIC, topic)
                }
            }
            context.sendBroadcast(intent)
            Log.d(TAG, "Notified SystemUI of active topic: $topic")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to send notifyTopicActive", e)
        }
    }
}
