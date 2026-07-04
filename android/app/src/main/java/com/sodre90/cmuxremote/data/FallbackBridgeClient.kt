package com.sodre90.cmuxremote.data

import com.sodre90.cmuxremote.model.FeedReply
import java.io.IOException

/**
 * Wraps a primary (relay) and fallback (direct) [BridgeClient] supplier.
 * Every call tries primary first and transparently retries against
 * fallback on a genuine reachability failure -- a transport-level
 * [IOException], or a [BridgeException] with a 5xx code (relay reachable,
 * but its tunnel to the Mac agent is broken) -- remembering the failure for
 * [PENALTY_MS] so a dead relay isn't re-tried on every single call. The
 * penalty is in-memory only -- a fresh process always tries primary first
 * again.
 *
 * A 4xx [BridgeException] from primary is an application-level error (bad
 * request, auth, stale pairing, etc.), not a reachability problem: it is
 * NOT treated as a failover trigger and propagates immediately with no
 * penalty set, so mutating calls (replyFeed/renameWorkspace/setYoloMode)
 * are never silently re-executed against the wrong backend.
 */
class FallbackBridgeClient(
    private val primary: () -> BridgeClient?,
    private val fallback: () -> BridgeClient?,
    private val now: () -> Long = System::currentTimeMillis,
) {
    @Volatile private var primaryDownUntil: Long = 0L

    private suspend fun <T> call(block: suspend (BridgeClient) -> T): T {
        val primaryClient = primary()
        val fallbackClient = fallback()

        // Skip a doomed primary attempt if it's not configured at all, or
        // we recently confirmed it's down (still inside the penalty window).
        val skipPrimary = primaryClient == null || now() < primaryDownUntil
        if (skipPrimary) {
            return block(fallbackClient ?: primaryClient ?: throw BridgeException(0, "not configured"))
        }

        return try {
            block(primaryClient)
        } catch (e: IOException) {
            if (fallbackClient == null) throw e
            if (e is BridgeException && e.code in 400..499) throw e
            primaryDownUntil = now() + PENALTY_MS
            block(fallbackClient)
        }
    }

    suspend fun sessions() = call { it.sessions() }
    suspend fun pendingFeed() = call { it.pendingFeed() }
    suspend fun replyFeed(feedId: String, reply: FeedReply) = call { it.replyFeed(feedId, reply) }
    suspend fun renameWorkspace(id: String, title: String) = call { it.renameWorkspace(id, title) }
    suspend fun setYoloMode(id: String, mode: String) = call { it.setYoloMode(id, mode) }

    private companion object {
        const val PENALTY_MS = 30_000L
    }
}
