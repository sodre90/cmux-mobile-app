package com.sodre90.cmuxremote.data.e2e

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sodre90.cmuxremote.data.ConnectionSlot

/** The subset of [Session] that [encryptBody]/[decryptBody]/[encryptFrame]/
 *  [decryptFrame] need -- lets tests substitute an in-memory fake. */
interface PairedSession {
    fun sharedSecret(): ByteArray?
    fun nextSendCounter(): Long
    fun canAcceptRecvCounter(n: Long): Boolean
    fun commitRecvCounter(n: Long)
}

/**
 * One paired-agent session for [slot]: the derived shared secret, a durable
 * monotonic send counter, and the sliding-window receive gate. The phone
 * pairs with exactly one agent per slot at a time -- re-pairing that slot
 * overwrites its own record, but the other slot's session is untouched
 * (both slots' keys share one prefs file, distinguished only by prefix).
 */
class Session(context: Context, private val slot: ConnectionSlot) : PairedSession {

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

    /**
     * Migrates the pre-dual-pairing single e2e session record into this
     * instance's slot, if [isMigrationTarget] is true. AppContainer decides
     * this once (from [com.sodre90.cmuxremote.data.Settings.migrateLegacyIfNeeded]'s
     * result), since a Session has no way to see the base URL its legacy
     * pairing belonged to and infer the right slot on its own. No-op if
     * there's no legacy record. Self-terminating: always clears the legacy
     * keys the first time it finds data.
     */
    fun absorbLegacyIfTarget(isMigrationTarget: Boolean) {
        if (!isMigrationTarget) return
        if (!prefs.contains(KEY_SHARED_SECRET)) return
        prefs.edit()
            .putString(key(KEY_PEER_PUBLIC_KEY), prefs.getString(KEY_PEER_PUBLIC_KEY, null))
            .putString(key(KEY_SHARED_SECRET), prefs.getString(KEY_SHARED_SECRET, null))
            .putLong(key(KEY_SEND_COUNTER), prefs.getLong(KEY_SEND_COUNTER, 0L))
            .putLong(key(KEY_RECV_HIGHEST), prefs.getLong(KEY_RECV_HIGHEST, -1L))
            .putLong(key(KEY_RECV_WINDOW_BITS), prefs.getLong(KEY_RECV_WINDOW_BITS, 0L))
            .remove(KEY_PEER_PUBLIC_KEY)
            .remove(KEY_SHARED_SECRET)
            .remove(KEY_SEND_COUNTER)
            .remove(KEY_RECV_HIGHEST)
            .remove(KEY_RECV_WINDOW_BITS)
            .apply()
    }

    fun isPaired(): Boolean = prefs.contains(key(KEY_SHARED_SECRET))

    override fun sharedSecret(): ByteArray? =
        prefs.getString(key(KEY_SHARED_SECRET), null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /** Called once, by [com.sodre90.cmuxremote.data.pairing.PairingClient] after a
     *  successful pairing handshake. Resets counters and the replay window --
     *  a fresh pairing means a fresh shared secret, so old counter state is
     *  meaningless (and reusing it would incorrectly reject the first messages). */
    fun setPairing(peerPublicKey: ByteArray, sharedSecret: ByteArray) {
        prefs.edit()
            .putString(key(KEY_PEER_PUBLIC_KEY), Base64.encodeToString(peerPublicKey, Base64.NO_WRAP))
            .putString(key(KEY_SHARED_SECRET), Base64.encodeToString(sharedSecret, Base64.NO_WRAP))
            .putLong(key(KEY_SEND_COUNTER), 0L)
            .putLong(key(KEY_RECV_HIGHEST), -1L)
            .putLong(key(KEY_RECV_WINDOW_BITS), 0L)
            .apply()
    }

    /** Durable, never reset across reconnects. */
    override fun nextSendCounter(): Long {
        val n = prefs.getLong(key(KEY_SEND_COUNTER), 0L)
        prefs.edit().putLong(key(KEY_SEND_COUNTER), n + 1).apply()
        return n
    }

    private fun replayWindow(): ReplayWindow =
        ReplayWindow(prefs.getLong(key(KEY_RECV_HIGHEST), -1L), prefs.getLong(key(KEY_RECV_WINDOW_BITS), 0L))

    /** Read-only check -- call before attempting to decrypt. */
    override fun canAcceptRecvCounter(n: Long): Boolean = replayWindow().canAccept(n)

    /** Mutating -- call only after the corresponding ciphertext has verified. */
    override fun commitRecvCounter(n: Long) {
        val updated = replayWindow().commit(n)
        prefs.edit()
            .putLong(key(KEY_RECV_HIGHEST), updated.highestSeen)
            .putLong(key(KEY_RECV_WINDOW_BITS), updated.windowBits)
            .apply()
    }

    /** Wipes this slot's session only -- used when re-pairing this slot. The
     *  other slot's session (sharing the same prefs file) is untouched. */
    fun clear() {
        prefs.edit()
            .remove(key(KEY_PEER_PUBLIC_KEY))
            .remove(key(KEY_SHARED_SECRET))
            .remove(key(KEY_SEND_COUNTER))
            .remove(key(KEY_RECV_HIGHEST))
            .remove(key(KEY_RECV_WINDOW_BITS))
            .apply()
    }

    private fun key(base: String) = "${slot.name.lowercase()}_$base"

    private companion object {
        const val PREFS_NAME = "cmux_e2e_session"
        const val KEY_PEER_PUBLIC_KEY = "device_public_key_b64"
        const val KEY_SHARED_SECRET = "shared_secret_b64"
        const val KEY_SEND_COUNTER = "send_counter"
        const val KEY_RECV_HIGHEST = "recv_highest"
        const val KEY_RECV_WINDOW_BITS = "recv_window_bits"
    }
}
