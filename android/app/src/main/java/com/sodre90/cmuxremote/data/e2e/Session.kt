package com.sodre90.cmuxremote.data.e2e

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/** The subset of [Session] that [encryptBody]/[decryptBody]/[encryptFrame]/
 *  [decryptFrame] need -- lets tests substitute an in-memory fake. */
interface PairedSession {
    fun sharedSecret(): ByteArray?
    fun nextSendCounter(): Long
    fun canAcceptRecvCounter(n: Long): Boolean
    fun commitRecvCounter(n: Long)
}

/**
 * The phone's single paired-agent session: the derived shared secret, a
 * durable monotonic send counter, and the sliding-window receive gate.
 * Unlike the Go agent (which pairs with many devices), the phone pairs with
 * exactly one agent at a time -- re-pairing overwrites this record.
 */
class Session(context: Context) : PairedSession {

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

    fun isPaired(): Boolean = prefs.contains(KEY_SHARED_SECRET)

    override fun sharedSecret(): ByteArray? =
        prefs.getString(KEY_SHARED_SECRET, null)?.let { Base64.decode(it, Base64.NO_WRAP) }

    /** Called once, by [com.sodre90.cmuxremote.data.pairing.PairingClient] after a
     *  successful pairing handshake. Resets counters and the replay window --
     *  a fresh pairing means a fresh shared secret, so old counter state is
     *  meaningless (and reusing it would incorrectly reject the first messages). */
    fun setPairing(peerPublicKey: ByteArray, sharedSecret: ByteArray) {
        prefs.edit()
            .putString(KEY_PEER_PUBLIC_KEY, Base64.encodeToString(peerPublicKey, Base64.NO_WRAP))
            .putString(KEY_SHARED_SECRET, Base64.encodeToString(sharedSecret, Base64.NO_WRAP))
            .putLong(KEY_SEND_COUNTER, 0L)
            .putLong(KEY_RECV_HIGHEST, -1L)
            .putLong(KEY_RECV_WINDOW_BITS, 0L)
            .apply()
    }

    /** Durable, never reset across reconnects (see Global Constraints). */
    override fun nextSendCounter(): Long {
        val n = prefs.getLong(KEY_SEND_COUNTER, 0L)
        prefs.edit().putLong(KEY_SEND_COUNTER, n + 1).apply()
        return n
    }

    private fun replayWindow(): ReplayWindow =
        ReplayWindow(prefs.getLong(KEY_RECV_HIGHEST, -1L), prefs.getLong(KEY_RECV_WINDOW_BITS, 0L))

    /** Read-only check -- call before attempting to decrypt. */
    override fun canAcceptRecvCounter(n: Long): Boolean = replayWindow().canAccept(n)

    /** Mutating -- call only after the corresponding ciphertext has verified. */
    override fun commitRecvCounter(n: Long) {
        val updated = replayWindow().commit(n)
        prefs.edit()
            .putLong(KEY_RECV_HIGHEST, updated.highestSeen)
            .putLong(KEY_RECV_WINDOW_BITS, updated.windowBits)
            .apply()
    }

    /** Wipes the whole session -- used when re-pairing or on the legacy-settings migration. */
    fun clear() {
        prefs.edit().clear().apply()
    }

    private companion object {
        const val PREFS_NAME = "cmux_e2e_session"
        const val KEY_PEER_PUBLIC_KEY = "device_public_key_b64"
        const val KEY_SHARED_SECRET = "shared_secret_b64"
        const val KEY_SEND_COUNTER = "send_counter"
        const val KEY_RECV_HIGHEST = "recv_highest"
        const val KEY_RECV_WINDOW_BITS = "recv_window_bits"
    }
}
