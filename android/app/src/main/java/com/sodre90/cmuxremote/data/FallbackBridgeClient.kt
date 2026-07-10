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
 * [registerDevice] is wrapped here too: whichever slot (relay or direct) is
 * actually reachable for a given device should end up with the FCM token,
 * since either one may be the connection the Mac agent later pushes through.
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
) {
    private suspend fun <T> call(block: suspend (BridgeClient) -> T): T {
        val primaryClient = primary()
        val fallbackClient = fallback()

        // Skip a doomed primary attempt if it's not configured at all, or
        // we recently confirmed it's down (still inside the penalty window).
        val skipPrimary = primaryClient == null || relayHealth.isDown(now())
        if (skipPrimary) {
            return block(fallbackClient ?: primaryClient ?: throw BridgeException(0, "not configured"))
        }

        return try {
            block(primaryClient)
        } catch (e: IOException) {
            if (fallbackClient == null) throw e
            if (e is BridgeException && e.code in 400..499) throw e
            relayHealth.markDown(now())
            block(fallbackClient)
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
    suspend fun registerDevice(fcmToken: String) = call { it.registerDevice(fcmToken) }

    private companion object {
        const val NOT_PAIRED_RETRY_ATTEMPTS = 3
        const val NOT_PAIRED_RETRY_DELAY_MS = 500L
    }
}
