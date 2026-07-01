package com.sodre90.cmuxremote

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import com.google.firebase.messaging.FirebaseMessaging
import com.sodre90.cmuxremote.ui.CmuxNavHost
import com.sodre90.cmuxremote.ui.theme.CmuxTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        val container = (application as CmuxApp).container
        registerFcmToken()

        val pendingWorkspaceId = intent?.getStringExtra(EXTRA_WORKSPACE_ID)
        val pendingSurfaceId = intent?.getStringExtra(EXTRA_SURFACE_ID)
        setContent {
            CmuxTheme {
                CmuxNavHost(
                    container,
                    pendingWorkspaceId = pendingWorkspaceId,
                    pendingSurfaceId = pendingSurfaceId,
                )
            }
        }
    }

    /**
     * Best-effort registration of the FCM token on launch. Firebase only
     * initialises when `app/google-services.json` is present; without it
     * [FirebaseMessaging.getInstance] throws, so this is a guarded no-op.
     */
    private fun registerFcmToken() {
        val container = (application as CmuxApp).container
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                scope.launch {
                    try {
                        container.bridgeClient()?.registerDevice(token)
                    } catch (_: Exception) {
                        // Bridge unreachable or unconfigured; retried next launch.
                    }
                }
            }
        } catch (_: Throwable) {
            // Firebase not configured (no google-services.json); push inactive.
        }
    }

    companion object {
        const val EXTRA_WORKSPACE_ID = "cmux.workspace_id"
        const val EXTRA_SURFACE_ID = "cmux.surface_id"
    }
}
