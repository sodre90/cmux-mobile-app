package com.sodre90.cmuxremote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.isActive

// A RELAY connect that drops again within this long of its first frame is
// treated as a framing failure, not a benign disconnect.
private const val RELAY_STABLE_MS = 2_000L

// How many framing failures in a row (see RELAY_STABLE_MS) before RELAY is
// penalized the same as a connect that received zero frames.
private const val RELAY_DROP_THRESHOLD = 3

/**
 * Shared reconnect loop for the app's streaming socket subscriptions
 * (terminal output, workspace events, inbox events). Owns: slot selection
 * that prefers DIRECT while RELAY is in [relayHealth]'s penalty window,
 * `?: other()` fallback when the preferred slot isn't configured at all,
 * exponential backoff with a cap between attempts, and the consecutive-
 * framing-failure escalation that promotes a flaky RELAY to a full penalty
 * (originally TerminalViewModel-only; every caller gets it now).
 *
 * All bookkeeping ([consecutiveRelayDrops], the loop's backoff) is instance
 * state with no synchronization, because -- like the ViewModels that own one
 * of these -- it is only ever driven from a single coroutine (Compose's Main
 * dispatcher). Don't call [run] concurrently on the same instance.
 */
class SocketReconnector<T>(
    private val relayHealth: RelayHealth,
    private val now: () -> Long = System::currentTimeMillis,
    private val initialBackoffMs: Long = INITIAL_BACKOFF_MS,
    private val maxBackoffMs: Long = MAX_BACKOFF_MS,
) {
    private var consecutiveRelayDrops = 0

    /**
     * Runs until the enclosing coroutine is cancelled ([CancellationException]
     * always rethrows). Each iteration: picks a primary [ConnectionSlot],
     * opens it via [openSocket] (falling back to the other slot if the
     * primary returns null, e.g. not configured), and collects frames
     * through [onFrame] -- whose return value says whether this frame
     * counts as healthy content, resetting backoff to [initialBackoffMs]
     * when true. [onConnected] fires once, when the first frame of a
     * connection arrives; [onDisconnected] fires once the socket ends
     * (gracefully or not); [onBeforeReconnect] fires right before the next
     * connect attempt, after the backoff delay.
     */
    suspend fun run(
        openSocket: (ConnectionSlot) -> Flow<T>?,
        onConnected: () -> Unit = {},
        onDisconnected: () -> Unit = {},
        onBeforeReconnect: () -> Unit = {},
        onFrame: suspend (T) -> Boolean,
    ) {
        var backoff = initialBackoffMs
        while (currentCoroutineContext().isActive) {
            val primarySlot = if (relayHealth.isDown(now())) ConnectionSlot.DIRECT else ConnectionSlot.RELAY
            val socket = openSocket(primarySlot) ?: openSocket(primarySlot.other())
            if (socket == null) {
                delay(backoff)
                continue
            }
            var gotFrame = false
            var connectedAtMs = 0L
            try {
                socket.collect { frame ->
                    if (!gotFrame) {
                        gotFrame = true
                        connectedAtMs = now()
                        onConnected()
                    }
                    if (onFrame(frame)) backoff = initialBackoffMs
                }
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                if (primarySlot == ConnectionSlot.RELAY) {
                    if (!gotFrame) {
                        relayHealth.markDown(now())
                        consecutiveRelayDrops = 0
                    } else if (now() - connectedAtMs < RELAY_STABLE_MS) {
                        consecutiveRelayDrops++
                        if (consecutiveRelayDrops >= RELAY_DROP_THRESHOLD) {
                            relayHealth.markDown(now())
                            consecutiveRelayDrops = 0
                        }
                    } else {
                        consecutiveRelayDrops = 0
                    }
                }
            }
            onDisconnected()
            if (!currentCoroutineContext().isActive) break
            delay(backoff)
            backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
            onBeforeReconnect()
        }
    }

    companion object {
        // Reconnect backoff: 1s, doubling to a 5s cap so a backgrounded app
        // reconnects within a few seconds of returning to the foreground.
        const val INITIAL_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 5_000L
    }
}
