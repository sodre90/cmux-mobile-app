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
import com.sodre90.cmuxremote.ui.Routes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Receives FCM pushes. On a data message with `type=attention` it posts a
 * notification that deep-links to the agent inbox; on a new token it registers
 * the device with the bridge. Firebase only initialises when
 * `app/google-services.json` is present — without it these callbacks never fire,
 * so the app still builds and runs with push simply inactive.
 */
class CmuxMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        val container = (application as? CmuxApp)?.container ?: return
        scope.launch {
            try {
                container.bridgeClient()?.registerDevice(token)
            } catch (_: Exception) {
                // Bridge unreachable or unconfigured; token is resent on next start.
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        if (message.data["type"] != "attention") return
        val title = message.data["title"]?.takeIf { it.isNotBlank() }
            ?: "Agent needs your attention"
        val body = message.data["kind"]?.takeIf { it.isNotBlank() } ?: "Open inbox to reply"
        showNotification(title, body)
    }

    private fun showNotification(title: String, body: String) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Agent attention", NotificationManager.IMPORTANCE_HIGH),
        )

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(MainActivity.EXTRA_NAV, Routes.INBOX)
        }
        val pending = PendingIntent.getActivity(
            this,
            0,
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

        nm.notify(System.currentTimeMillis().toInt(), notification)
    }

    companion object {
        const val CHANNEL_ID = "agent_attention"
    }
}
