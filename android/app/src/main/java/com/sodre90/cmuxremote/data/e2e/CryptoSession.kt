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
import kotlinx.coroutines.runBlocking

/** The subset of [CryptoSession] that [encryptBody]/[decryptBody]/[encryptFrame]/
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
class CryptoSession(context: Context, private val slot: ConnectionSlot) : PairedSession {

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
    // `this` and routes its prefs write through writeScope (persist() or the
    // blocking persistDurably()), so (a) callers on different threads
    // (Compose's main thread for sends, an OkHttp callback thread for
    // recvs, the pairing/forget flows) never see a torn read, and (b) the
    // on-disk value always converges to match whichever write was most
    // recently applied in memory -- a queued write from before a reset
    // can't land after it and resurrect stale counters, since writeScope is
    // single-threaded FIFO regardless of which of the two helpers enqueued
    // it. nextSendCounter/commitRecvCounter use persistDurably specifically
    // because their value is handed out (as an AEAD nonce input) before
    // this class can know it was ever durably written; setPairing/clear
    // reset both fields to fixed constants, so a lost write there just
    // reappears identically on the next mutation, not as a reusable nonce.
    // peer_pubkey/shared_secret are deliberately NOT part of this cache:
    // isPaired()/sharedSecret() read them straight from prefs and need
    // read-after-write consistency, so their writes stay synchronous
    // exactly as before.
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

    // Send/recv counters back the AEAD nonce, so a lost write is a security
    // bug, not just a stale cache: apply() only queues the disk write and can
    // be dropped entirely by a process death (crash/OOM-kill/force-stop)
    // before it lands, unlike commit(), which blocks until the write is on
    // disk. Blocking here (via runBlocking) keeps the same writeScope
    // ordering persist() relies on elsewhere -- still one queue, one writer
    // -- while guaranteeing the counter this call just handed out can never
    // be re-issued after a restart. The callers (encryptFrame/decryptFrame,
    // on the terminal socket's send path and OkHttp's WS reader thread; the
    // e2e HTTP interceptor, on OkHttp's dispatcher pool) are already
    // throttled well above single-digit-millisecond disk-write latency by
    // DeliveryTracker's one-RPC-in-flight gate and OkHttp's own connection
    // handling, so this doesn't introduce user-visible jank.
    private fun persistDurably(block: SharedPreferences.Editor.() -> Unit) {
        val job = writeScope.launch {
            val editor = prefs.edit()
            editor.block()
            editor.commit()
        }
        runBlocking { job.join() }
    }

    /**
     * Migrates the pre-dual-pairing single e2e session record into this
     * instance's slot, if [isMigrationTarget] is true. AppContainer decides
     * this once (from [com.sodre90.cmuxremote.data.Settings.migrateLegacyIfNeeded]'s
     * result), since a CryptoSession has no way to see the base URL its legacy
     * pairing belonged to and infer the right slot on its own. No-op if
     * there's no legacy record. Self-terminating: always clears the legacy
     * keys the first time it finds data. The migrate-or-not decision and the
     * legacy-record shape are factored into [absorbLegacyIfTargetInternal]
     * so a JVM test can exercise them against in-memory maps; this method
     * keeps only what needs the real prefs/in-memory-cache/writeScope.
     */
    fun absorbLegacyIfTarget(isMigrationTarget: Boolean) {
        val record = absorbLegacyIfTargetInternal(
            isMigrationTarget = isMigrationTarget,
            hasLegacyRecord = { prefs.contains(KEY_SHARED_SECRET) },
            readLegacyRecord = {
                LegacySessionRecord(
                    peerPublicKeyB64 = prefs.getString(KEY_PEER_PUBLIC_KEY, null),
                    sharedSecretB64 = requireNotNull(prefs.getString(KEY_SHARED_SECRET, null)),
                    sendCounter = prefs.getLong(KEY_SEND_COUNTER, 0L),
                    recvHighest = prefs.getLong(KEY_RECV_HIGHEST, -1L),
                    recvWindowBits = prefs.getLong(KEY_RECV_WINDOW_BITS, 0L),
                )
            },
            applyMigration = { record ->
                prefs.edit()
                    .putString(key(KEY_PEER_PUBLIC_KEY), record.peerPublicKeyB64)
                    .putString(key(KEY_SHARED_SECRET), record.sharedSecretB64)
                    .remove(KEY_PEER_PUBLIC_KEY)
                    .remove(KEY_SHARED_SECRET)
                    .remove(KEY_SEND_COUNTER)
                    .remove(KEY_RECV_HIGHEST)
                    .remove(KEY_RECV_WINDOW_BITS)
                    .apply()
            },
        ) ?: return
        synchronized(this) {
            sendCounter = record.sendCounter
            replayWindow = ReplayWindow(record.recvHighest, record.recvWindowBits)
        }
        persist {
            putLong(key(KEY_SEND_COUNTER), record.sendCounter)
            putLong(key(KEY_RECV_HIGHEST), record.recvHighest)
            putLong(key(KEY_RECV_WINDOW_BITS), record.recvWindowBits)
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

    /** Durable -- the bump is on disk before this returns, never reset across
     *  reconnects -- so a value handed out here can never be handed out
     *  again after a crash/kill, which would otherwise reuse an AEAD nonce. */
    override fun nextSendCounter(): Long {
        val n = synchronized(this) {
            val current = sendCounter
            sendCounter = current + 1
            current
        }
        persistDurably { putLong(key(KEY_SEND_COUNTER), n + 1) }
        return n
    }

    /** Read-only check -- call before attempting to decrypt. */
    override fun canAcceptRecvCounter(n: Long): Boolean = synchronized(this) { replayWindow.canAccept(n) }

    /** Mutating -- call only after the corresponding ciphertext has verified.
     *  Durable before returning, so a crash/kill can't re-widen the replay
     *  window back to a point that would re-accept an already-processed
     *  (captured) frame. */
    override fun commitRecvCounter(n: Long) {
        val updated = synchronized(this) {
            replayWindow = replayWindow.commit(n)
            replayWindow
        }
        persistDurably {
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

/** The legacy pre-dual-pairing e2e session fields [absorbLegacyIfTargetInternal]
 *  moves into whichever slot's [CryptoSession] is the migration target. */
internal data class LegacySessionRecord(
    val peerPublicKeyB64: String?,
    val sharedSecretB64: String,
    val sendCounter: Long,
    val recvHighest: Long,
    val recvWindowBits: Long,
)

/** Free function form of [CryptoSession.absorbLegacyIfTarget], parameterized over
 *  plain read/write callbacks so a JVM test can exercise it against
 *  in-memory maps instead of real EncryptedSharedPreferences -- mirrors
 *  [com.sodre90.cmuxremote.data.pairing.pairInternal]'s injectable-I/O
 *  pattern. Returns the migrated record (for the caller to also update its
 *  own in-memory counter cache), or null if [isMigrationTarget] is false or
 *  there was nothing to migrate. */
internal fun absorbLegacyIfTargetInternal(
    isMigrationTarget: Boolean,
    hasLegacyRecord: () -> Boolean,
    readLegacyRecord: () -> LegacySessionRecord,
    applyMigration: (LegacySessionRecord) -> Unit,
): LegacySessionRecord? {
    if (!isMigrationTarget) return null
    if (!hasLegacyRecord()) return null
    val record = readLegacyRecord()
    applyMigration(record)
    return record
}
