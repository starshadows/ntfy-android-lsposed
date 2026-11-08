package io.heckel.ntfy.xposed

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import io.heckel.ntfy.app.Application
import io.heckel.ntfy.msg.NotificationDispatcher
import io.heckel.ntfy.msg.NotificationParser
import io.heckel.ntfy.service.SubscriberService
import io.heckel.ntfy.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch

class NtfySyncReceiver : BroadcastReceiver() {
    companion object {
        const val TAG = "NtfySyncReceiver"
    }

    private val parser = NotificationParser()

    override fun onReceive(context: Context, intent: Intent?) {
        val app = context.applicationContext as? Application ?: return
        val repository = app.repository

        when (intent?.action) {
            NtfyBridge.ACTION_SYSTEM_NOTIFICATION_ARRIVED -> {
                val json = intent.getStringExtra(NtfyBridge.EXTRA_NOTIFICATION_JSON) ?: return
                val baseUrl = intent.getStringExtra(NtfyBridge.EXTRA_BASE_URL) ?: ""
                Log.d(TAG, "Notification arrived from SystemUI: $json (baseUrl: $baseUrl)")
                val pendingResult = goAsync()
                GlobalScope.launch(Dispatchers.IO) {
                    try {
                        val parsed = parser.parseWithTopic(json, baseUrl = baseUrl)
                        if (parsed != null) {
                            val subscription = (if (baseUrl.isNotEmpty()) repository.getSubscription(baseUrl, parsed.topic) else null)
                                ?: repository.getSubscription(parsed.notification.subscriptionId)
                                ?: repository.getSubscriptions().firstOrNull { it.topic == parsed.topic && (baseUrl.isEmpty() || it.baseUrl == baseUrl) }
                                ?: repository.getSubscriptions().firstOrNull { it.topic == parsed.topic }

                            val subId = subscription?.id ?: 0L
                            val currentViewingSubId = repository.detailViewSubscriptionId.get()
                            val isCurrentlyViewing = (currentViewingSubId != 0L && currentViewingSubId == subId)
                            val notifIdToSave = if (isCurrentlyViewing) 0 else parsed.notification.notificationId

                            val notificationWithCorrectSubId = parsed.notification.copy(subscriptionId = subId, notificationId = notifIdToSave)
                            repository.addNotification(notificationWithCorrectSubId)
                            Log.d(TAG, "Saved notification #${notificationWithCorrectSubId.id} to local repository (isCurrentlyViewing=$isCurrentlyViewing, notifId=$notifIdToSave)")

                            if (isCurrentlyViewing) {
                                NtfyBridge.clearSystemNotifications(context, topic = parsed.topic)
                            }
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to save incoming notification from SystemUI", e)
                    } finally {
                        try {
                            pendingResult.finish()
                        } catch (_: Exception) {
                        }
                    }
                }
            }
            NtfyBridge.ACTION_REQUEST_SYNC_SUBSCRIPTIONS -> {
                Log.d(TAG, "SystemUI requested subscription sync, sending current configuration...")
                NtfyBridge.syncToSystemUI(context, repository)
            }
            NtfyBridge.ACTION_REPLY_LSP_STATUS -> {
                val active = intent.getBooleanExtra(NtfyBridge.EXTRA_LSP_ACTIVE, false)
                if (active) {
                    NtfyBridge.isXposedHookActive = true
                    Log.i(TAG, "SystemUI confirmed LSPosed is active, stopping any app foreground service")
                    try {
                        val svcIntent = Intent(context, SubscriberService::class.java).apply {
                            action = SubscriberService.Action.STOP.name
                        }
                        context.startService(svcIntent)
                    } catch (e: Exception) {
                        Log.w(TAG, "Failed to send stop action to SubscriberService: ${e.message}")
                    }
                }
            }
        }
    }
}
