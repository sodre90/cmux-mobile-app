package com.sodre90.cmuxremote

import android.Manifest
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.google.firebase.FirebaseApp
import com.google.firebase.messaging.FirebaseMessaging
import com.sodre90.cmuxremote.data.AppContainer
import com.sodre90.cmuxremote.ui.CmuxNavHost
import com.sodre90.cmuxremote.ui.theme.CmuxTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val requestNotifications =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    // Backed by Compose state (not plain vals read once in onCreate) so a
    // notification tap while this task is already running - the common case,
    // since singleTask launchMode reuses the instance via onNewIntent instead
    // of a fresh onCreate - still reaches CmuxNavHost's deep-link resolution.
    private var pendingWorkspaceId by mutableStateOf<String?>(null)
    private var pendingSurfaceId by mutableStateOf<String?>(null)

    // Bumped on every applyDeepLink call, independent of whether the ids
    // above actually changed value. A repeat notification for the same
    // workspace (e.g. the same agent pinging again) would otherwise leave
    // pendingWorkspaceId equal to its previous value, and CmuxNavHost's
    // LaunchedEffect only restarts when a keyed value changes - so without
    // this token, re-tapping such a notification silently did nothing.
    private var pendingDeepLinkToken by mutableIntStateOf(0)

    // Held so onStart/onStop can flip the process-wide foreground flag the
    // streaming subscriptions pause on (see AppContainer.setAppForeground).
    private lateinit var container: AppContainer

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && isFirebaseConfigured()) {
            requestNotifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        container = (application as CmuxApp).container
        registerFcmToken()

        applyDeepLink(intent)
        setContent {
            CmuxTheme {
                CmuxNavHost(
                    container,
                    pendingWorkspaceId = pendingWorkspaceId,
                    pendingSurfaceId = pendingSurfaceId,
                    pendingDeepLinkToken = pendingDeepLinkToken,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        applyDeepLink(intent)
    }

    override fun onStart() {
        super.onStart()
        container.setAppForeground(true)
    }

    // Pauses every streaming socket subscription and its event-driven
    // refetches for as long as the user is away -- the single biggest lever
    // on cellular data usage, since viewModelScope alone keeps them running
    // with the screen off. Push notifications cover attention while paused.
    override fun onStop() {
        super.onStop()
        container.setAppForeground(false)
    }

    private fun applyDeepLink(intent: Intent) {
        pendingWorkspaceId = intent.getStringExtra(EXTRA_WORKSPACE_ID)
        pendingSurfaceId = intent.getStringExtra(EXTRA_SURFACE_ID)
        pendingDeepLinkToken++
    }

    /**
     * Firebase only initialises when `app/google-services.json` is present;
     * without it [FirebaseApp.getInstance] throws. Without push configured
     * there's nothing to notify about, so don't prompt for the permission.
     */
    private fun isFirebaseConfigured(): Boolean = try {
        FirebaseApp.getInstance()
        true
    } catch (_: Throwable) {
        false
    }

    /**
     * Best-effort registration of the FCM token on launch. Firebase only
     * initialises when `app/google-services.json` is present; without it
     * [FirebaseMessaging.getInstance] throws, so this is a guarded no-op.
     */
    private fun registerFcmToken() {
        val container = (application as CmuxApp).container
        try {
            FirebaseMessaging.getInstance().token.addOnSuccessListener { token ->
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        container.activeBridge()?.registerDevice(token)
                    } catch (e: Exception) {
                        // Retried next launch. Per-slot rejections have already
                        // been recorded by then (see FallbackBridgeClient's
                        // onRegistrationOutcome), so this only reports that no
                        // slot at all took the token -- which used to vanish
                        // without trace.
                        Log.w(TAG, "device registration failed on every slot: ${e.message}")
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
        private const val TAG = "MainActivity"
    }
}
