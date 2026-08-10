package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.model.FeedReply
import com.sodre90.cmuxremote.model.PendingFeedItem
import com.sodre90.cmuxremote.model.Workspace
import kotlinx.coroutines.delay
import java.io.IOException

/**
 * Wraps a primary (relay) and fallback (direct) [BridgeClient] supplier.
 * Every call tries primary first and transparently retries against
 * fallback on a genuine reachability failure -- a transport-level
 * [IOException], or a [BridgeException] with a 5xx code (relay reachable,
 * but its tunnel to the Mac agent is broken) -- remembering the failure in
 * [relayHealth] so a dead relay isn't re-tried on every single call. By
 * default each instance gets its own private [RelayHealth] (a fresh process
 * always tries primary first again); [AppContainer] passes one shared
 * instance so this and [SocketReconnector] learn "relay is down" once.
 *
 * A 4xx [BridgeException] from primary is an application-level error (bad
 * request, auth, stale pairing, etc.), not a reachability problem: it is
 * NOT treated as a failover trigger and propagates immediately with no
 * penalty set, so mutating calls (replyFeed/renameWorkspace/setYoloMode/
 * registerDevice) are never silently re-executed against the wrong backend.
 *
 * [registerDevice] is the one exception to the try-primary-then-fallback
 * shape: it fans out to BOTH slots instead, because each keeps its own
 * device table and either may be the connection a push later arrives
 * through. See its own doc for why "whichever is reachable" was not enough.
 *
 * [sessions] and [pendingFeed] additionally retry a 409 not_paired through
 * [retryingNotPaired] -- see its doc for why. Every caller of these two reads
 * inherits that retry automatically; nothing else needs to duplicate it.
 */
class FallbackBridgeClient(
    private val primary: () -> BridgeClient?,
    private val fallback: () -> BridgeClient?,
    private val now: () -> Long = System::currentTimeMillis,
    private val relayHealth: RelayHealth = RelayHealth(),
    private val pairingRetryDelayMs: Long = NOT_PAIRED_RETRY_DELAY_MS,
    private val monitor: ConnectionMonitor = ConnectionMonitor(),
) {
    private suspend fun <T> call(block: suspend (BridgeClient) -> T): T {
        val primaryClient = primary()
        val fallbackClient = fallback()

        // Skip a doomed primary attempt if it's not configured at all, or
        // we recently confirmed it's down (still inside the penalty window).
        val skipPrimary = primaryClient == null || relayHealth.isDown(now())
        if (skipPrimary) {
            val only = fallbackClient ?: primaryClient ?: throw BridgeException(0, "not configured")
            val slot = if (only === primaryClient) ConnectionSlot.RELAY else ConnectionSlot.DIRECT
            if (slot == ConnectionSlot.DIRECT && primaryClient != null) monitor.fallingBack(null)
            return try {
                block(only).also {
                    if (slot == ConnectionSlot.RELAY) relayHealth.markUp()
                    monitor.connected(slot)
                }
            } catch (e: IOException) {
                // Report the skip: "direct failed" alone reads as though relay
                // was fine, when in fact it was never tried this time round.
                if (primaryClient != null && only !== primaryClient) {
                    throw BothTransportsFailedException(relayError = null, directError = e)
                        .also { monitor.failed(it.message.orEmpty()) }
                }
                throw e
            }
        }

        monitor.connecting(ConnectionSlot.RELAY)
        return try {
            block(primaryClient).also {
                // Proof the relay works, which is what wakes a socket
                // subscription still parked on DIRECT -- see RelayHealth.recoveries.
                relayHealth.markUp()
                monitor.connected(ConnectionSlot.RELAY)
            }
        } catch (e: IOException) {
            if (fallbackClient == null) throw e
            if (e is BridgeException && e.code in 400..499) throw e
            relayHealth.markDown(now())
            monitor.fallingBack(e.describeForUser())
            try {
                block(fallbackClient).also { monitor.connected(ConnectionSlot.DIRECT) }
            } catch (fallbackError: IOException) {
                throw BothTransportsFailedException(relayError = e, directError = fallbackError)
                    .also { monitor.failed(it.message.orEmpty()) }
            }
        }
    }

    /**
     * Retries [block] a few times when it throws a 409 not_paired
     * [BridgeException]. Right after pairing completes, the phone can call a
     * read endpoint before the Mac agent's pair-device poll loop has derived
     * and stored the e2e session -- the relay authenticates the device's
     * token fine, but the agent replies 409 not_paired for that narrow
     * window. Deliberately not applied to the mutating calls below: a
     * retried write could double-apply once the race clears, so
     * replyFeed/renameWorkspace/setYoloMode/registerDevice propagate a 409
     * immediately, same as any other 4xx (see [call]'s doc).
     */
    private suspend fun <T> retryingNotPaired(block: suspend () -> T): T {
        repeat(NOT_PAIRED_RETRY_ATTEMPTS - 1) {
            try {
                return block()
            } catch (e: BridgeException) {
                if (e.code != 409) throw e
                delay(pairingRetryDelayMs)
            }
        }
        return block()
    }

    suspend fun sessions(): List<Workspace> = retryingNotPaired { call { it.sessions() } }
    suspend fun pendingFeed(): List<PendingFeedItem> = retryingNotPaired { call { it.pendingFeed() } }
    suspend fun replyFeed(feedId: String, reply: FeedReply) = call { it.replyFeed(feedId, reply) }
    suspend fun renameWorkspace(id: String, title: String) = call { it.renameWorkspace(id, title) }
    suspend fun setYoloMode(id: String, mode: String) = call { it.setYoloMode(id, mode) }
    /**
     * Registers on EVERY configured slot, not just whichever answers first.
     *
     * The relay and the agent keep separate device tables, and neither can
     * fill the other's in: the relay answers /devices/register itself
     * (bridge/internal/relay/relay.go) and the agent serves it only on its
     * direct route set, never on the relay-tunneled one (see the note in
     * bridge/internal/server/trusted.go). Registering just the first
     * reachable slot therefore left the direct slot with no token at all
     * while the relay was up -- the relay answers 200 whenever its own
     * process is alive, even with its tunnel to the agent dead, so [call]
     * never had cause to fail over -- and direct-mode push was silently
     * dead exactly when a relay outage made it the only path left
     * (cmux-app-vex).
     *
     * Deliberately not routed through [call]: that helper stops at the first
     * success, and would read this endpoint's 200 as evidence the relay is
     * healthy when it evidences only that the relay process is running.
     *
     * Best effort per slot -- one slot failing must not deny the other its
     * token -- so this throws only when no slot accepted the token.
     */
    suspend fun registerDevice(fcmToken: String) {
        val targets = listOfNotNull(primary(), fallback())
        if (targets.isEmpty()) throw BridgeException(0, "not configured")
        var registered = false
        var lastFailure: IOException? = null
        for (target in targets) {
            try {
                target.registerDevice(fcmToken)
                registered = true
            } catch (e: IOException) {
                lastFailure = e
            }
        }
        if (!registered) throw lastFailure ?: BridgeException(0, "not configured")
    }

    /** Same fallback philosophy as [registerDevice]: whichever slot is
     *  actually reachable handles the test push, since either one may be
     *  the connection real pushes later arrive through. */
    suspend fun sendTestPush() = call { it.sendTestPush() }

    private companion object {
        const val NOT_PAIRED_RETRY_ATTEMPTS = 3
        const val NOT_PAIRED_RETRY_DELAY_MS = 500L
    }
}

/**
 * Raised when neither transport could serve a call. Both causes are kept and
 * both appear in [message], because they usually fail for unrelated reasons --
 * the relay being unreachable (or its tunnel to the Mac being down) says
 * nothing about whether Tailscale is up on this phone, and collapsing them
 * into one "connection failed" hides which half to go and fix. ViewModels
 * surface [message] verbatim, so no call site needs to know this type exists.
 *
 * [relayError] is null when the relay was inside [RelayHealth]'s penalty
 * window and so was never attempted for this call.
 */
class BothTransportsFailedException(
    val relayError: IOException?,
    val directError: IOException,
) : IOException(
    "Relay: ${relayError?.describeForUser() ?: "skipped (recently unreachable)"} • " +
        "Direct: ${directError.describeForUser()}",
)

internal fun IOException.describeForUser(): String =
    (message ?: this::class.simpleName ?: "unreachable").take(MAX_CAUSE_CHARS)

private const val MAX_CAUSE_CHARS = 120
