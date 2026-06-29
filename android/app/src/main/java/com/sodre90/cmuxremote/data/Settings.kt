package com.sodre90.cmuxremote.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists connection settings and secrets. Everything (including the base URL
 * and token) lives in [EncryptedSharedPreferences] so the device certificate and
 * bearer token are encrypted at rest; nothing here is ever logged.
 */
class Settings(context: Context) {

    private val prefs: SharedPreferences = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    var baseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, null)
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var deviceToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    var serverCaPem: String?
        get() = prefs.getString(KEY_CA_PEM, null)
        set(value) = prefs.edit().putString(KEY_CA_PEM, value).apply()

    val p12Password: String?
        get() = prefs.getString(KEY_P12_PASSWORD, null)

    val hasClientCert: Boolean
        get() = prefs.contains(KEY_P12)

    fun setClientP12(bytes: ByteArray, password: String) {
        prefs.edit()
            .putString(KEY_P12, Base64.encodeToString(bytes, Base64.NO_WRAP))
            .putString(KEY_P12_PASSWORD, password)
            .apply()
    }

    fun clientP12(): ByteArray? =
        prefs.getString(KEY_P12, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /** Assembles a [BridgeConfig], or null if base URL or token is not yet set. */
    fun bridgeConfig(): BridgeConfig? {
        val url = baseUrl?.takeIf { it.isNotBlank() } ?: return null
        val token = deviceToken?.takeIf { it.isNotBlank() } ?: return null
        return BridgeConfig(
            baseUrl = url,
            deviceToken = token,
            clientP12 = clientP12(),
            p12Password = p12Password.orEmpty(),
            serverCaPem = serverCaPem,
        )
    }

    private companion object {
        const val PREFS_NAME = "cmux_secure_prefs"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "device_token"
        const val KEY_CA_PEM = "server_ca_pem"
        const val KEY_P12 = "client_p12_b64"
        const val KEY_P12_PASSWORD = "client_p12_password"
    }
}
