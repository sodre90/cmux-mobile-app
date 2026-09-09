package com.sodre90.cmuxremote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sodre90.cmuxremote.push.PendingTokenStore

/**
 * Persists connection settings and secrets for both [ConnectionSlot]s. Everything
 * (including base URLs and tokens) lives in [EncryptedSharedPreferences] so
 * device certificates and bearer tokens are encrypted at rest; nothing here is
 * ever logged.
 *
 * Also the durable half of [SlotCredentialHealth]: the rest of that state is
 * in-memory and starts over each process, but whether the user has already
 * been told about a rejection has to outlive one (see [RejectionReportLog]).
 */
class Settings(context: Context) : RejectionReportLog, PendingTokenStore {

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

    /**
     * An FCM token no slot has accepted yet, kept so the retry can outlive the
     * process that failed.
     *
     * FCM hands the app a rotated token exactly once, through onNewToken. If the
     * bridge happens to be unreachable at that moment the token used to be
     * dropped on the floor and retried only if the user next opened the app --
     * so an update installed overnight left the server holding a dead token,
     * which FCM accepts with a 2xx while delivering nothing (cmux-app-2cm).
     *
     * Not cleared on [clearSlot]: the token belongs to the app on this device,
     * not to either slot, and a re-pair should still find it waiting.
     */
    override fun pendingFcmToken(): String? = prefs.getString(KEY_PENDING_FCM_TOKEN, null)

    override fun setPendingFcmToken(token: String?) {
        prefs.edit().apply {
            if (token == null) remove(KEY_PENDING_FCM_TOKEN) else putString(KEY_PENDING_FCM_TOKEN, token)
        }.apply()
    }

    /**
     * The Firebase client config the bridge handed over at pairing, or null if
     * no pairing has supplied one. Not slot-scoped -- see [FcmClientConfig].
     *
     * Read on every launch before Firebase is touched, so a phone that has
     * never paired against a push-configured bridge simply never initialises
     * Firebase at all.
     */
    fun fcmClientConfig(): FcmClientConfig? {
        val projectId = prefs.getString(KEY_FCM_PROJECT_ID, null).orEmpty()
        val appId = prefs.getString(KEY_FCM_APP_ID, null).orEmpty()
        val apiKey = prefs.getString(KEY_FCM_API_KEY, null).orEmpty()
        val senderId = prefs.getString(KEY_FCM_SENDER_ID, null).orEmpty()
        if (projectId.isBlank() || appId.isBlank() || apiKey.isBlank() || senderId.isBlank()) return null
        return FcmClientConfig(projectId, appId, apiKey, senderId)
    }

    /**
     * Stores the config [slot]'s bridge supplied, or clears the stored one when
     * that bridge supplied none.
     *
     * The clear is deliberately narrow. Only the slot that supplied the stored
     * config may clear it: the two slots are configured independently, and
     * direct push is documented as optional, so pairing a push-less direct
     * agent after a push-enabled relay would otherwise delete a working
     * config and kill push on both slots. Same reasoning as the FCM token two
     * blocks up -- what belongs to the app on this device does not get thrown
     * away by whichever slot happened to re-pair last.
     */
    fun setFcmClientConfig(slot: ConnectionSlot, config: FcmClientConfig?) {
        val owner = prefs.getString(KEY_FCM_SOURCE_SLOT, null)
        if (config == null && !mayClearFcmConfig(owner, slot)) return
        prefs.edit().apply {
            if (config == null) {
                remove(KEY_FCM_PROJECT_ID)
                remove(KEY_FCM_APP_ID)
                remove(KEY_FCM_API_KEY)
                remove(KEY_FCM_SENDER_ID)
                remove(KEY_FCM_SOURCE_SLOT)
            } else {
                putString(KEY_FCM_PROJECT_ID, config.projectId)
                putString(KEY_FCM_APP_ID, config.appId)
                putString(KEY_FCM_API_KEY, config.apiKey)
                putString(KEY_FCM_SENDER_ID, config.senderId)
                putString(KEY_FCM_SOURCE_SLOT, slot.name)
            }
        }.apply()
    }

    override fun wasRejectionReported(slot: ConnectionSlot): Boolean =
        prefs.getBoolean(key(slot, KEY_REJECTION_REPORTED), false)

    override fun setRejectionReported(slot: ConnectionSlot, reported: Boolean) {
        prefs.edit().putBoolean(key(slot, KEY_REJECTION_REPORTED), reported).apply()
    }

    /** Wipes [slot]'s stored base URL and device token -- used by "Forget" in
     *  ConnectionSettingsScreen. The other slot is untouched. */
    fun clearSlot(slot: ConnectionSlot) {
        prefs.edit()
            .remove(key(slot, KEY_BASE_URL))
            .remove(key(slot, KEY_TOKEN))
            .remove(key(slot, KEY_REJECTION_REPORTED))
            .apply()
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
     * from init): its result tells AppContainer which CryptoSession instance
     * should absorb the matching legacy e2e session data, since CryptoSession
     * has no way to see the base URL and infer this on its own.
     *
     * Returns the slot migrated into, or null if there was nothing to
     * migrate (already migrated on a prior run, or a genuinely fresh
     * install). Self-terminating: always clears the legacy keys the first
     * time it finds data, so this never fires twice. The actual decision
     * logic lives in [migrateLegacyIfNeededInternal] -- this is a thin
     * wrapper supplying the real prefs-backed read/write callbacks.
     */
    fun migrateLegacyIfNeeded(): ConnectionSlot? = migrateLegacyIfNeededInternal(
        readLegacyBaseUrl = { prefs.getString(KEY_BASE_URL, null) },
        readLegacyToken = { prefs.getString(KEY_TOKEN, null) },
        applyMigration = { slot, url, token ->
            prefs.edit()
                .putString(key(slot, KEY_BASE_URL), url)
                .putString(key(slot, KEY_TOKEN), token)
                .remove(KEY_BASE_URL)
                .remove(KEY_TOKEN)
                .apply()
        },
    )

    private fun key(slot: ConnectionSlot, base: String) = "${slot.name.lowercase()}_$base"

    private companion object {
        const val PREFS_NAME = "cmux_secure_prefs"
        const val KEY_BASE_URL = "base_url"
        const val KEY_TOKEN = "device_token"
        const val KEY_P12 = "client_p12_b64"
        const val KEY_REJECTION_REPORTED = "credential_rejection_reported"

        // Not slot-scoped: one FCM token per app install, offered to every slot.
        const val KEY_PENDING_FCM_TOKEN = "pending_fcm_token"

        // Not slot-scoped either: Firebase initialises once per process, so
        // one config serves both slots (see FcmClientConfig).
        const val KEY_FCM_PROJECT_ID = "fcm_project_id"
        const val KEY_FCM_APP_ID = "fcm_app_id"
        const val KEY_FCM_API_KEY = "fcm_api_key"
        const val KEY_FCM_SENDER_ID = "fcm_sender_id"

        // Which slot's bridge supplied the stored config, so only that slot
        // can later clear it (see setFcmClientConfig).
        const val KEY_FCM_SOURCE_SLOT = "fcm_source_slot"
    }
}

/**
 * Whether [slot] is allowed to clear a stored FCM config owned by [owner]
 * (null meaning nobody has claimed one).
 *
 * A free function for the same reason [migrateLegacyIfNeededInternal] is one:
 * the decision is worth testing on the JVM, and [Settings] itself cannot be
 * constructed without Android Keystore.
 */
internal fun mayClearFcmConfig(owner: String?, slot: ConnectionSlot): Boolean =
    owner == null || owner == slot.name

/** Free function form of [Settings.migrateLegacyIfNeeded], parameterized over
 *  plain read/write callbacks so a JVM test can exercise it against
 *  in-memory maps instead of real EncryptedSharedPreferences -- mirrors
 *  [com.sodre90.cmuxremote.data.pairing.commitInternal]'s injectable-I/O
 *  pattern. [applyMigration] is expected to persist [ConnectionSlot]'s new
 *  base_url/device_token and clear the legacy keys in one atomic commit,
 *  same as the real prefs transaction it replaces. */
internal fun migrateLegacyIfNeededInternal(
    readLegacyBaseUrl: () -> String?,
    readLegacyToken: () -> String?,
    applyMigration: (slot: ConnectionSlot, baseUrl: String, token: String) -> Unit,
): ConnectionSlot? {
    val legacyUrl = readLegacyBaseUrl()?.takeIf { it.isNotBlank() } ?: return null
    val legacyToken = readLegacyToken()?.takeIf { it.isNotBlank() } ?: return null
    val slot = inferLegacySlot(legacyUrl)
    applyMigration(slot, legacyUrl, legacyToken)
    return slot
}
