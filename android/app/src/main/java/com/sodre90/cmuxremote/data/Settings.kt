package com.sodre90.cmuxremote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Persists connection settings and secrets for both [ConnectionSlot]s. Everything
 * (including base URLs and tokens) lives in [EncryptedSharedPreferences] so
 * device certificates and bearer tokens are encrypted at rest; nothing here is
 * ever logged.
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
        // again once cleared, so this branch never fires on later launches.
        if (prefs.contains(KEY_P12)) {
            prefs.edit().clear().apply()
        }
    }

    fun baseUrl(slot: ConnectionSlot): String? = prefs.getString(key(slot, KEY_BASE_URL), null)
    fun setBaseUrl(slot: ConnectionSlot, value: String) {
        prefs.edit().putString(key(slot, KEY_BASE_URL), value).apply()
    }

    fun deviceToken(slot: ConnectionSlot): String? = prefs.getString(key(slot, KEY_TOKEN), null)
    fun setDeviceToken(slot: ConnectionSlot, value: String) {
        prefs.edit().putString(key(slot, KEY_TOKEN), value).apply()
    }

    /** Assembles a [BridgeConfig] for [slot], or null if that slot has never
     *  been paired. */
    fun bridgeConfig(slot: ConnectionSlot): BridgeConfig? {
        val url = baseUrl(slot)?.takeIf { it.isNotBlank() } ?: return null
        val token = deviceToken(slot)?.takeIf { it.isNotBlank() } ?: return null
        return BridgeConfig(baseUrl = url, deviceToken = token)
    }

    /**
     * One-time migration from the pre-dual-pairing single {base_url,
     * device_token} pair into whichever slot it most likely belongs to (see
     * [inferLegacySlot]). Must be called explicitly by AppContainer (not
     * from init): its result tells AppContainer which Session instance
     * should absorb the matching legacy e2e session data, since Session has
     * no way to see the base URL and infer this on its own.
     *
     * Returns the slot migrated into, or null if there was nothing to
     * migrate (already migrated on a prior run, or a genuinely fresh
     * install). Self-terminating: always clears the legacy keys the first
     * time it finds data, so this never fires twice.
     */
    fun migrateLegacyIfNeeded(): ConnectionSlot? {
        val legacyUrl = prefs.getString(KEY_BASE_URL, null)?.takeIf { it.isNotBlank() } ?: return null
        val legacyToken = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val slot = inferLegacySlot(legacyUrl)
        prefs.edit()
            .putString(key(slot, KEY_BASE_URL), legacyUrl)
            .putString(key(slot, KEY_TOKEN), legacyToken)
            .remove(KEY_BASE_URL)
            .remove(KEY_TOKEN)
            .apply()
        return slot
    }

    private fun key(slot: ConnectionSlot, base: String) = "${slot.name.lowercase()}_$base"

    private companion object {
        const val PREFS_NAME = "cmux_secure_prefs"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "device_token"
        const val KEY_P12 = "client_p12_b64"
    }
}
