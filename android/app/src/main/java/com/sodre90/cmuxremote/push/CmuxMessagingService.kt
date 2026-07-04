package com.sodre90.cmuxremote.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sodre90.cmuxremote.CmuxApp
import com.sodre90.cmuxremote.MainActivity
import com.sodre90.cmuxremote.data.ConnectionSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives FCM pushes. On a data message with `type=attention` it posts a
 * notification that deep-links to the workspace that needs attention (resolved
 * to its exact terminal by CmuxNavHost, since cmux never tells us the pane); on
 * a new token it registers the device with the bridge. Firebase only
 * initialises when `app/google-services.json` is present — without it these
 * callbacks never fire, so the app still builds and runs with push inactive.
 */
class CmuxMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val container = (application as? CmuxApp)?.container ?: return
        scope.launch {
            try {
                container.bridgeClient(ConnectionSlot.RELAY)?.registerDevice(token)
            } catch (_: Exception) {
                // Bridge unreachable or unconfigured; token is resent on next start.
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "attention") return
        val title = message.data["title"]?.takeIf { it.isNotBlank() }
            ?: "Agent needs your attention"
        // body carries the workspace's live title + status preview (the richest
        // context cmux exposes for a prompt whose text it redacts).
        val body = message.data["body"]?.takeIf { it.isNotBlank() }
            ?: message.data["kind"]?.takeIf { it.isNotBlank() }
            ?: "Open cmux to reply"
        showNotification(
            title,
            body,
            workspaceId = message.data["workspace_id"]?.takeIf { it.isNotBlank() },
            surfaceId = message.data["surface_id"]?.takeIf { it.isNotBlank() },
        )
    }

    private fun showNotification(title: String, body: String, workspaceId: String?, surfaceId: String?) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent attention", NotificationManager.IMPORTANCE_HIGH),
        )

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_WORKSPACE_ID, workspaceId)
            putExtra(MainActivity.EXTRA_SURFACE_ID, surfaceId)
        }
        // Stable per-workspace id -- the relay pushes once per NeedsAttention
        // frame with no dedup, and cmux can emit more than one of those for a
        // prompt that's still pending. Keying on workspaceId means a repeat
        // push updates the same notification tile instead of stacking a new
        // one for the same terminal.
        val notificationId = (workspaceId ?: surfaceId ?: "attention").hashCode()
        val pending = PendingIntent.getActivity(
            this,
            notificationId,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle(title)
            .setContentText(body)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()

        nm.notify(notificationId, notification)
    }

    companion object {
        const val CHANNEL_ID = "agent_attention"
    }
}
