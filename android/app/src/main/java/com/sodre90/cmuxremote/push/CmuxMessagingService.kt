package com.sodre90.cmuxremote.push

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import com.sodre90.cmuxremote.CmuxApp
import com.sodre90.cmuxremote.MainActivity
import com.sodre90.cmuxremote.data.AppContainer
import com.sodre90.cmuxremote.data.ConnectionSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * Receives FCM pushes. On a data message with `type=attention` it posts a
 * notification that deep-links to the workspace that needs attention (resolved
 * to its exact terminal by CmuxNavHost, since cmux never tells us the pane).
 * `type=test` (see ConnectionSettingsScreen's "Send test notification") posts
 * the same way but with no workspace to deep-link to. On a new token it
 * registers the device with the bridge. Firebase only initialises when
 * `app/google-services.json` is present — without it these callbacks never
 * fire, so the app still builds and runs with push inactive.
 */
class CmuxMessagingService : FirebaseMessagingService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    override fun onNewToken(token: String) {
        val container = (application as? CmuxApp)?.container ?: return
        scope.launch {
            try {
                container.activeBridge()?.registerDevice(token)
            } catch (e: Exception) {
                // Token is resent on next start. Per-slot rejections are
                // already recorded by FallbackBridgeClient's
                // onRegistrationOutcome; this is only the no-slot-took-it case.
                Log.w(TAG, "device registration failed on every slot: ${e.message}")
            }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val type = message.data["type"]
        if (type != "attention" && type != "test") return
        val container = (application as? CmuxApp)?.container
        val (title, body) = decryptContent(container, message.data) ?: (GENERIC_TITLE to GENERIC_BODY)
        showNotification(
            title,
            body,
            workspaceId = message.data["workspace_id"]?.takeIf { it.isNotBlank() },
            surfaceId = message.data["surface_id"]?.takeIf { it.isNotBlank() },
        )
    }

    /**
     * Decrypts the per-device e2e payload the bridge/relay embed under
     * `data["e2e"]` -- the bridge never sends real title/body in the clear (see
     * bridge/internal/server/push.go's buildEncryptedPush), so this is the only
     * way to recover the actual notification content. Returns null on anything
     * that isn't a clean decrypt: no session/key material yet, wrong slot,
     * corrupt blob, or an unpaired/wiped local session. The caller falls back to
     * one generic, content-free notification in every such case.
     */
    private fun decryptContent(container: AppContainer?, data: Map<String, String>): Pair<String, String>? {
        val container = container ?: return null
        val blobB64 = data["e2e"] ?: return null
        val slot = data["slot"]?.let { name -> ConnectionSlot.entries.find { it.name.equals(name, ignoreCase = true) } }
            ?: return null
        return try {
            val payload = decryptPushPayload(container.session(slot), container.cipher, blobB64)
            payload.title to payload.body
        } catch (_: Exception) {
            null
        }
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
        val notificationId = attentionNotificationId(workspaceId, surfaceId)
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
        private const val TAG = "CmuxMessagingService"
        private const val GENERIC_TITLE = "cmux needs your attention"
        private const val GENERIC_BODY = "Open the app to see what's happening"
    }
}

/**
 * The notification id an attention/test push is filed under -- shared between
 * the poster ([CmuxMessagingService.showNotification]) and anything that later
 * needs to cancel it (e.g. CmuxNavHost, once the workspace it points at is
 * actually opened) so the two never drift apart.
 */
fun attentionNotificationId(workspaceId: String?, surfaceId: String?): Int =
    (workspaceId ?: surfaceId ?: "attention").hashCode()
