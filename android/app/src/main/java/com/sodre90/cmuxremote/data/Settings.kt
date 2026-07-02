package com.sodre90.cmuxremote.data

import android.content.Context
import android.content.SharedPreferences
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

    init {
        // An upgrading install may still have the pre-pairing manual-setup
        // format's client-cert key on disk. Wipe the whole prefs file once
        // and force re-pairing. Self-terminating: nothing writes this key
        // again once cleared (Task 18 removes the code path that ever did),
        // so this branch never fires on later launches.
        if (prefs.contains(KEY_P12)) {
            prefs.edit().clear().apply()
        }
    }

    var baseUrl: String?
        get() = prefs.getString(KEY_BASE_URL, null)
        set(value) = prefs.edit().putString(KEY_BASE_URL, value).apply()

    var deviceToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) = prefs.edit().putString(KEY_TOKEN, value).apply()

    /** Assembles a [BridgeConfig], or null if base URL or token is not yet set. */
    fun bridgeConfig(): BridgeConfig? {
        val url = baseUrl?.takeIf { it.isNotBlank() } ?: return null
        val token = deviceToken?.takeIf { it.isNotBlank() } ?: return null
        return BridgeConfig(baseUrl = url, deviceToken = token)
    }

    private companion object {
        const val PREFS_NAME = "cmux_secure_prefs"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "device_token"
        const val KEY_P12 = "client_p12_b64"
    }
}
