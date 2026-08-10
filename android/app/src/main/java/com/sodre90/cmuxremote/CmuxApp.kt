package com.sodre90.cmuxremote

import android.app.Application
import com.sodre90.cmuxremote.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class CmuxApp : Application() {

    // AppContainer's construction (Settings/CryptoSession) does
    // EncryptedSharedPreferences + Android Keystore I/O, which can block for
    // a while (key generation on first launch, TEE round-trips thereafter).
    // `by lazy`'s default synchronization means whichever caller -- this
    // warm-up or the first real UI access -- gets there first does the real
    // work, and everyone else just reads the result.
    val container: AppContainer by lazy(LazyThreadSafetyMode.SYNCHRONIZED) { AppContainer(this) }

    override fun onCreate() {
        super.onCreate()
        // Warm up container on a background thread so this onCreate call --
        // watched by the ANR/startup-time machinery -- returns immediately
        // instead of blocking on Keystore I/O.
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch { container }
    }
}

/** Convenience accessor for the app-wide container from any Context. */
val Application.container: AppContainer
    get() = (this as CmuxApp).container
