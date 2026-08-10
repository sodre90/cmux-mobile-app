package com.sodre90.cmuxremote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.IOException

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
 * A socket that had to settle for DIRECT does not stay there once RELAY is
 * working again. Slot choice happens per connect attempt, and a healthy
 * socket never disconnects, so without this a single relay blip would pin a
 * long-lived subscription to DIRECT indefinitely -- far past
 * [RelayHealth]'s penalty window. Such a socket watches
 * [RelayHealth.recoveries] and ends itself when RELAY is proven healthy
 * again, letting the next iteration re-pick it.
 *
 * That same "a healthy socket never disconnects" property is why every
 * connection also watches [SlotCredentials] for its own slot: Forget and
 * re-pair replace the credentials in storage, but the connection already
 * open goes on using the old ones until something ends it (cmux-app-2zn,
 * cmux-app-smu).
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
    private val monitor: ConnectionMonitor = ConnectionMonitor(),
    private val slotCredentials: SlotCredentials = SlotCredentials(),
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
            // Read before the slot decision: any recovery or credential
            // change from here on must wake us, including one landing while
            // we're still connecting. Both slots are sampled because the one
            // we end up on isn't known until openSocket's fallback resolves.
            val recoveriesAtPick = relayHealth.recoveries.value
            val credentialsAtPick = ConnectionSlot.entries.associateWith { slotCredentials.generation(it).value }
            val relayPenalized = relayHealth.isDown(now())
            val primarySlot = if (relayPenalized) ConnectionSlot.DIRECT else ConnectionSlot.RELAY
            if (relayPenalized) monitor.fallingBack(null) else monitor.connecting(ConnectionSlot.RELAY)
            var slot = primarySlot
            val socket = openSocket(primarySlot) ?: openSocket(primarySlot.other())?.also { slot = primarySlot.other() }
            if (socket == null) {
                delay(backoff)
                continue
            }
            var gotFrame = false
            var connectedAtMs = 0L
            // Only when parked on DIRECT *because* RELAY was penalized. A
            // DIRECT socket that is simply the configured one has nothing to
            // come back to.
            val parkedOnDirect = relayPenalized && slot == ConnectionSlot.DIRECT
            var relayRecovered = false
            var credentialsReplaced = false
            try {
                coroutineScope {
                    val connection = this
                    // Cancelling the scope cancels the collect below, closing
                    // the socket via its awaitClose so the loop re-picks.
                    val watchers = mutableListOf<Job>()
                    if (parkedOnDirect) {
                        watchers += launch {
                            relayHealth.recoveries.first { it != recoveriesAtPick }
                            relayRecovered = true
                            connection.cancel()
                        }
                    }
                    watchers += launch {
                        slotCredentials.generation(slot).first { it != credentialsAtPick.getValue(slot) }
                        credentialsReplaced = true
                        connection.cancel()
                    }
                    // Collected in this coroutine rather than a child: a
                    // CancellationException raised by the flow itself has to
                    // reach the caller, and a child job would absorb it as
                    // ordinary cancellation.
                    socket.collect { frame ->
                        if (!gotFrame) {
                            gotFrame = true
                            connectedAtMs = now()
                            monitor.connected(slot)
                            onConnected()
                        }
                        if (onFrame(frame)) backoff = initialBackoffMs
                    }
                    watchers.forEach { it.cancel() }
                }
            } catch (e: CancellationException) {
                // Our own voluntary stop cancels only the inner scope, so the
                // enclosing coroutine is still active; anything else -- the
                // caller being cancelled, or the flow cancelling itself --
                // must propagate.
                if (!((relayRecovered || credentialsReplaced) && currentCoroutineContext().isActive)) throw e
            } catch (e: Exception) {
                monitor.failed(if (e is IOException) e.describeForUser() else e.message.orEmpty())
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
            if (relayRecovered || credentialsReplaced) {
                // A deliberate switch, not a failure: reconnect straight away
                // rather than serving out a backoff nothing earned.
                backoff = initialBackoffMs
            } else {
                delay(backoff)
                backoff = (backoff * 2).coerceAtMost(maxBackoffMs)
            }
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
