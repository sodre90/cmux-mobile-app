package com.sodre90.cmuxremote.ui.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import com.sodre90.cmuxremote.data.Settings

/** Backs the onboarding screen: base URL, device token, optional CA, and the
 *  PKCS#12 client certificate. Persists straight into [Settings]. */
class SettingsViewModel(private val settings: Settings) : ViewModel() {

    var baseUrl by mutableStateOf(settings.baseUrl.orEmpty())
    var token by mutableStateOf(settings.deviceToken.orEmpty())
    var caPem by mutableStateOf(settings.serverCaPem.orEmpty())
    var p12Password by mutableStateOf(settings.p12Password.orEmpty())

    var p12Imported by mutableStateOf(settings.hasClientCert)
        private set
    var status by mutableStateOf<String?>(null)

    val isComplete: Boolean
        get() = baseUrl.isNotBlank() && token.isNotBlank()

    fun onP12Picked(bytes: ByteArray) {
        settings.setClientP12(bytes, p12Password)
        p12Imported = true
        status = "Imported client certificate (${bytes.size} bytes)"
    }

    fun save() {
        settings.baseUrl = baseUrl.trim()
        settings.deviceToken = token.trim()
        settings.serverCaPem = caPem.trim().ifBlank { null }
        if (p12Imported && p12Password.isNotBlank()) {
            // keep password in sync if the user edited it after importing
            settings.clientP12()?.let { settings.setClientP12(it, p12Password) }
        }
        status = "Saved"
    }
}
