package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.model.FeedReply
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
 */
class FallbackBridgeClient(
    private val primary: () -> BridgeClient?,
    private val fallback: () -> BridgeClient?,
    private val now: () -> Long = System::currentTimeMillis,
    private val relayHealth: RelayHealth = RelayHealth(),
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

    suspend fun sessions() = call { it.sessions() }
    suspend fun pendingFeed() = call { it.pendingFeed() }
    suspend fun replyFeed(feedId: String, reply: FeedReply) = call { it.replyFeed(feedId, reply) }
    suspend fun renameWorkspace(id: String, title: String) = call { it.renameWorkspace(id, title) }
    suspend fun setYoloMode(id: String, mode: String) = call { it.setYoloMode(id, mode) }
    suspend fun registerDevice(fcmToken: String) = call { it.registerDevice(fcmToken) }
}
