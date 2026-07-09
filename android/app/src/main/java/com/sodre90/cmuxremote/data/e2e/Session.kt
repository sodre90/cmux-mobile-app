package com.sodre90.cmuxremote.data.e2e

import android.content.Context
import android.content.SharedPreferences
import android.util.Base64
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.sodre90.cmuxremote.data.ConnectionSlot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

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

    // In-memory mirror of the send counter and replay window, so the hot
    // path (once per outbound keystroke / inbound frame) never re-decrypts
    // these from EncryptedSharedPreferences. Every method that touches
    // either field -- including setPairing/clear/absorbLegacyIfTarget, not
    // just the hot-path nextSendCounter/commitRecvCounter -- synchronizes on
    // `this` and routes its prefs write through persist() on writeScope, so
    // (a) callers on different threads (Compose's main thread for sends, an
    // OkHttp callback thread for recvs, the pairing/forget flows) never see
    // a torn read, and (b) the on-disk value always converges to match
    // whichever write was most recently applied in memory -- a queued write
    // from before a reset can't land after it and resurrect stale counters,
    // since writeScope is single-threaded FIFO. peer_pubkey/shared_secret
    // are deliberately NOT part of this cache: isPaired()/sharedSecret()
    // read them straight from prefs and need read-after-write consistency,
    // so their writes stay synchronous exactly as before.
    private var sendCounter: Long = prefs.getLong(key(KEY_SEND_COUNTER), 0L)
    private var replayWindow: ReplayWindow =
        ReplayWindow(prefs.getLong(key(KEY_RECV_HIGHEST), -1L), prefs.getLong(key(KEY_RECV_WINDOW_BITS), 0L))

    // Single-threaded so writes are applied in the same order they were
    // committed in memory -- Dispatchers.IO's shared pool alone would not
    // guarantee that for independently launched coroutines.
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    private fun persist(block: SharedPreferences.Editor.() -> Unit) {
        writeScope.launch {
            val editor = prefs.edit()
            editor.block()
            editor.apply()
        }
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
        val send = prefs.getLong(KEY_SEND_COUNTER, 0L)
        val recvHighest = prefs.getLong(KEY_RECV_HIGHEST, -1L)
        val recvWindowBits = prefs.getLong(KEY_RECV_WINDOW_BITS, 0L)
        prefs.edit()
            .putString(key(KEY_PEER_PUBLIC_KEY), prefs.getString(KEY_PEER_PUBLIC_KEY, null))
            .putString(key(KEY_SHARED_SECRET), prefs.getString(KEY_SHARED_SECRET, null))
            .remove(KEY_PEER_PUBLIC_KEY)
            .remove(KEY_SHARED_SECRET)
            .remove(KEY_SEND_COUNTER)
            .remove(KEY_RECV_HIGHEST)
            .remove(KEY_RECV_WINDOW_BITS)
            .apply()
        synchronized(this) {
            sendCounter = send
            replayWindow = ReplayWindow(recvHighest, recvWindowBits)
        }
        persist {
            putLong(key(KEY_SEND_COUNTER), send)
            putLong(key(KEY_RECV_HIGHEST), recvHighest)
            putLong(key(KEY_RECV_WINDOW_BITS), recvWindowBits)
        }
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
            .apply()
        synchronized(this) {
            sendCounter = 0L
            replayWindow = ReplayWindow()
        }
        persist {
            putLong(key(KEY_SEND_COUNTER), 0L)
            putLong(key(KEY_RECV_HIGHEST), -1L)
            putLong(key(KEY_RECV_WINDOW_BITS), 0L)
        }
    }

    /** Durable, never reset across reconnects. */
    override fun nextSendCounter(): Long {
        val n = synchronized(this) {
            val current = sendCounter
            sendCounter = current + 1
            current
        }
        persist { putLong(key(KEY_SEND_COUNTER), n + 1) }
        return n
    }

    /** Read-only check -- call before attempting to decrypt. */
    override fun canAcceptRecvCounter(n: Long): Boolean = synchronized(this) { replayWindow.canAccept(n) }

    /** Mutating -- call only after the corresponding ciphertext has verified. */
    override fun commitRecvCounter(n: Long) {
        val updated = synchronized(this) {
            replayWindow = replayWindow.commit(n)
            replayWindow
        }
        persist {
            putLong(key(KEY_RECV_HIGHEST), updated.highestSeen)
            putLong(key(KEY_RECV_WINDOW_BITS), updated.windowBits)
        }
    }

    /** Wipes this slot's session only -- used when re-pairing this slot. The
     *  other slot's session (sharing the same prefs file) is untouched. */
    fun clear() {
        prefs.edit()
            .remove(key(KEY_PEER_PUBLIC_KEY))
            .remove(key(KEY_SHARED_SECRET))
            .apply()
        synchronized(this) {
            sendCounter = 0L
            replayWindow = ReplayWindow()
        }
        persist {
            remove(key(KEY_SEND_COUNTER))
            remove(key(KEY_RECV_HIGHEST))
            remove(key(KEY_RECV_WINDOW_BITS))
        }
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
