package com.sodre90.cmuxremote.data

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/**
 * Drives [SocketReconnector] on the coroutine test scheduler's virtual
 * clock -- the reconnector's injectable `now` reads `currentTime`, so
 * backoff delays and penalty windows are exact and instant.
 */
class SocketReconnectorTest {

    private class Attempt(val slot: ConnectionSlot, val atMs: Long)

    @Test
    fun prefersRelayWhileHealthyAndDirectDuringPenaltyWindow() = runTest {
        val health = RelayHealth()
        val reconnector = SocketReconnector<Int>(health, now = { currentTime })
        val attempts = mutableListOf<Attempt>()
        val job = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                if (attempts.size == 1) health.markDown(currentTime)
                flow { awaitCancellation() }
            }) { true }
        }

        delay(1)
        assertEquals(ConnectionSlot.RELAY, attempts.single().slot)

        // Simulate the socket dying while RELAY is penalized (e.g. the REST
        // path's FallbackBridgeClient marked it down via the same shared
        // RelayHealth): the next attempt must prefer DIRECT.
        job.cancelAndJoin()
        val secondJob = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                flow { awaitCancellation() }
            }) { true }
        }
        delay(1)
        assertEquals(ConnectionSlot.DIRECT, attempts[1].slot)

        // Once the penalty lapses, RELAY is primary again.
        secondJob.cancelAndJoin()
        delay(RelayHealth.DEFAULT_PENALTY_MS)
        val thirdJob = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                flow { awaitCancellation() }
            }) { true }
        }
        delay(1)
        assertEquals(ConnectionSlot.RELAY, attempts[2].slot)
        thirdJob.cancelAndJoin()
    }

    @Test
    fun relayConnectWithZeroFramesPenalizesRelayAndFlipsToDirect() = runTest {
        val health = RelayHealth()
        val reconnector = SocketReconnector<Int>(health, now = { currentTime })
        val attempts = mutableListOf<Attempt>()
        val job = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                if (attempts.size == 1) {
                    flow {
                        throw IOException(
                            "relay unreachable"
                        )
                    }
                } else {
                    flow { awaitCancellation() }
                }
            }) { true }
        }

        delay(2_000)
        assertEquals(ConnectionSlot.RELAY, attempts[0].slot)
        assertEquals(ConnectionSlot.DIRECT, attempts[1].slot)
        assertTrue(health.isDown(currentTime))
        job.cancelAndJoin()
    }

    @Test
    fun unconfiguredPrimaryFallsBackToTheOtherSlotInTheSameAttempt() = runTest {
        val reconnector = SocketReconnector<Int>(RelayHealth(), now = { currentTime })
        val attempts = mutableListOf<Attempt>()
        val job = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                if (slot == ConnectionSlot.RELAY) null else flow { awaitCancellation() }
            }) { true }
        }

        delay(1)
        assertEquals(listOf(ConnectionSlot.RELAY, ConnectionSlot.DIRECT), attempts.map { it.slot })
        assertEquals(listOf(0L, 0L), attempts.map { it.atMs })
        job.cancelAndJoin()
    }

    @Test
    fun backoffDoublesToTheCapAcrossFramelessFailures() = runTest {
        val reconnector = SocketReconnector<Int>(RelayHealth(), now = { currentTime })
        val attempts = mutableListOf<Attempt>()
        val job = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                flow { throw IOException("unreachable") }
            }) { true }
        }

        delay(18_000)
        job.cancelAndJoin()
        // 0, +1s, +2s, +4s, then capped at +5s.
        assertEquals(listOf(0L, 1_000L, 3_000L, 7_000L, 12_000L, 17_000L), attempts.map { it.atMs })
    }

    @Test
    fun aHealthyFrameResetsBackoffToInitial() = runTest {
        val reconnector = SocketReconnector<Int>(RelayHealth(), now = { currentTime })
        val attempts = mutableListOf<Attempt>()
        val job = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                when (attempts.size) {
                    1, 2 -> flow { throw IOException("unreachable") } // backoff grows to 2s
                    3 -> flow {
                        emit(1) // healthy frame resets backoff
                        delay(10_000) // outlives RELAY_STABLE_MS: a benign drop, no penalty
                        throw IOException("dropped")
                    }
                    else -> flow { awaitCancellation() }
                }
            }) { true }
        }

        delay(20_000)
        job.cancelAndJoin()
        // Attempt 4 comes 1s (initial backoff, not the grown 4s) after
        // attempt 3's connection dies at 3_000 + 10_000.
        assertEquals(listOf(0L, 1_000L, 3_000L, 14_000L), attempts.map { it.atMs })
    }

    @Test
    fun aFrameTheCallerRejectsDoesNotResetBackoff() = runTest {
        val reconnector = SocketReconnector<Int>(RelayHealth(), now = { currentTime })
        val attempts = mutableListOf<Attempt>()
        val job = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                when (attempts.size) {
                    1, 2 -> flow { throw IOException("unreachable") } // backoff grows to 2s
                    3 -> flow<Int> {
                        emit(1) // ack-like frame: onFrame returns false
                        delay(10_000)
                        throw IOException("dropped")
                    }
                    else -> flow { awaitCancellation() }
                }
            }) { false }
        }

        delay(25_000)
        job.cancelAndJoin()
        // Attempt 4 comes 4s (the grown backoff) after attempt 3 dies.
        assertEquals(listOf(0L, 1_000L, 3_000L, 17_000L), attempts.map { it.atMs })
    }

    @Test
    fun repeatedQuickRelayDropsAfterFramingTriggerTheDirectPreference() = runTest {
        val health = RelayHealth()
        val reconnector = SocketReconnector<Int>(health, now = { currentTime })
        val attempts = mutableListOf<Attempt>()
        val job = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                if (slot == ConnectionSlot.RELAY) {
                    flow {
                        emit(1) // frames fine...
                        throw IOException("dropped right away") // ...but dies within RELAY_STABLE_MS
                    }
                } else {
                    flow { awaitCancellation() }
                }
            }) { true }
        }

        delay(4_000)
        // Three quick post-framing drops in a row penalize RELAY the same as
        // a zero-frame connect; the fourth attempt prefers DIRECT.
        assertEquals(
            listOf(ConnectionSlot.RELAY, ConnectionSlot.RELAY, ConnectionSlot.RELAY, ConnectionSlot.DIRECT),
            attempts.map { it.slot },
        )
        assertTrue(health.isDown(currentTime))
        job.cancelAndJoin()
    }

    @Test
    fun aStableRelayConnectionResetsTheQuickDropCounter() = runTest {
        val health = RelayHealth()
        val reconnector = SocketReconnector<Int>(health, now = { currentTime })
        val attempts = mutableListOf<Attempt>()
        val job = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                when (attempts.size) {
                    1, 2 -> flow {
                        emit(1)
                        throw IOException("quick drop")
                    }
                    3 -> flow {
                        emit(1)
                        delay(10_000) // stable: outlives RELAY_STABLE_MS
                        throw IOException("benign drop")
                    }
                    4, 5 -> flow {
                        emit(1)
                        throw IOException("quick drop")
                    }
                    else -> flow { awaitCancellation() }
                }
            }) { true }
        }

        delay(30_000)
        // Two quick drops, a stable connection (counter reset), then two more
        // quick drops: never three in a row, so RELAY is never penalized.
        assertEquals(6, attempts.size)
        assertTrue(attempts.all { it.slot == ConnectionSlot.RELAY })
        assertFalse(health.isDown(currentTime))
        job.cancelAndJoin()
    }

    @Test
    fun cancellationDuringCollectStopsPromptlyWithoutPenalizingRelay() = runTest {
        val health = RelayHealth()
        val reconnector = SocketReconnector<Int>(health, now = { currentTime })
        var disconnects = 0
        var attempts = 0
        val job = launch {
            reconnector.run(
                openSocket = { _ ->
                    attempts++
                    flow { awaitCancellation() }
                },
                onDisconnected = { disconnects++ },
            ) { true }
        }

        delay(1)
        job.cancelAndJoin()
        assertTrue(job.isCancelled)
        assertEquals(1, attempts)
        // CancellationException rethrows before the failure classification
        // and the onDisconnected bookkeeping run.
        assertEquals(0, disconnects)
        assertFalse(health.isDown(currentTime))
    }

    @Test
    fun cancellationExceptionFromTheFlowRethrows() = runTest {
        val reconnector = SocketReconnector<Int>(RelayHealth(), now = { currentTime })
        var thrown: Throwable? = null
        val job = launch {
            try {
                reconnector.run(openSocket = { _ -> flow { throw CancellationException("stop") } }) { true }
            } catch (e: CancellationException) {
                thrown = e
                throw e
            }
        }

        job.join()
        assertEquals("stop", thrown?.message)
    }

    @Test
    fun cancellationDuringBackoffDelayStopsTheLoop() = runTest {
        val reconnector = SocketReconnector<Int>(RelayHealth(), now = { currentTime })
        var attempts = 0
        val job = launch {
            reconnector.run(openSocket = { _ ->
                attempts++
                flow { throw IOException("unreachable") }
            }) { true }
        }

        delay(500) // mid-backoff (first retry would fire at 1s)
        job.cancelAndJoin()
        delay(5_000)
        assertEquals(1, attempts)
    }

    /**
     * cmux-app-424: a healthy DIRECT socket never disconnects, so without a
     * recovery watch it would never re-pick a slot and would stay on DIRECT
     * long after a momentary relay blip.
     */
    @Test
    fun socketParkedOnDirectReturnsToRelayOnProvenRecovery() = runTest {
        val health = RelayHealth()
        val reconnector = SocketReconnector<Int>(health, now = { currentTime })
        val attempts = mutableListOf<Attempt>()
        health.markDown(currentTime)
        val job = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                flow { awaitCancellation() } // healthy socket: never drops
            }) { true }
        }

        delay(1)
        assertEquals(ConnectionSlot.DIRECT, attempts.single().slot)

        // The window lapsing is NOT proof: a socket must not churn every
        // penalty window while the relay is genuinely still down.
        delay(RelayHealth.DEFAULT_PENALTY_MS * 10)
        assertEquals(1, attempts.size)

        // The REST path reaches the relay again and reports it.
        health.markUp()
        delay(1)
        assertEquals(ConnectionSlot.RELAY, attempts[1].slot)
        job.cancelAndJoin()
    }

    /**
     * The recovery signal is a counter read before the slot is chosen, so a
     * recovery landing while the socket is still opening still wakes it. An
     * edge-triggered signal would be lost in that gap -- and since only the
     * down-to-up edge reports, no later success would report it again.
     */
    @Test
    fun recoveryLandingDuringConnectIsNotMissed() = runTest {
        val health = RelayHealth()
        val reconnector = SocketReconnector<Int>(health, now = { currentTime })
        val attempts = mutableListOf<Attempt>()
        health.markDown(currentTime)
        val job = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                if (attempts.size == 1) health.markUp()
                flow { awaitCancellation() }
            }) { true }
        }

        delay(1)
        assertEquals(ConnectionSlot.DIRECT, attempts[0].slot)
        assertEquals(ConnectionSlot.RELAY, attempts[1].slot)
        job.cancelAndJoin()
    }

    /** A socket on DIRECT because RELAY isn't configured has nothing to
     *  return to, and must not be torn down when RELAY recovers. */
    @Test
    fun directOnlySocketIsNotDisturbedByRelayRecovery() = runTest {
        val health = RelayHealth()
        val reconnector = SocketReconnector<Int>(health, now = { currentTime })
        val attempts = mutableListOf<Attempt>()
        val job = launch {
            reconnector.run(openSocket = { slot ->
                if (slot == ConnectionSlot.RELAY) {
                    null
                } else {
                    attempts.add(Attempt(slot, currentTime))
                    flow { awaitCancellation() }
                }
            }) { true }
        }

        delay(1)
        assertEquals(ConnectionSlot.DIRECT, attempts.single().slot)

        health.markDown(currentTime)
        health.markUp()
        delay(1)
        assertEquals(1, attempts.size)
        job.cancelAndJoin()
    }

    /**
     * cmux-app-2zn. Forgetting a slot only rewrites storage; the socket
     * already open keeps streaming over the slot the user just forgot,
     * because a healthy socket never disconnects and the loop re-picks only
     * between connections. Invalidating the slot has to end it.
     */
    @Test
    fun forgettingTheConnectedSlotEndsItsSocketAndMovesToTheOtherOne() = runTest {
        val health = RelayHealth()
        val credentials = SlotCredentials()
        val reconnector = SocketReconnector<Int>(health, now = { currentTime }, slotCredentials = credentials)
        val attempts = mutableListOf<Attempt>()
        var relayForgotten = false
        val job = launch {
            reconnector.run(openSocket = { slot ->
                if (slot == ConnectionSlot.RELAY && relayForgotten) {
                    null // exactly what eventsSocket() returns once the config is cleared
                } else {
                    attempts.add(Attempt(slot, currentTime))
                    flow { awaitCancellation() } // healthy socket: never drops on its own
                }
            }) { true }
        }

        delay(1)
        assertEquals(ConnectionSlot.RELAY, attempts.single().slot)

        relayForgotten = true
        credentials.invalidate(ConnectionSlot.RELAY)
        delay(1)

        assertEquals("the forgotten slot's socket must not survive", 2, attempts.size)
        assertEquals(ConnectionSlot.DIRECT, attempts[1].slot)
        job.cancelAndJoin()
    }

    /**
     * cmux-app-smu. Re-pairing keeps the slot configured but replaces its
     * token, so the still-open socket stays mapped to the agent's previous
     * device row and every frame it carries fails to decrypt. It has to
     * reconnect on the new credentials rather than wait for a restart.
     */
    @Test
    fun rePairingTheConnectedSlotReconnectsOnTheSameSlot() = runTest {
        val health = RelayHealth()
        val credentials = SlotCredentials()
        val reconnector = SocketReconnector<Int>(health, now = { currentTime }, slotCredentials = credentials)
        val attempts = mutableListOf<Attempt>()
        val job = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                flow { awaitCancellation() }
            }) { true }
        }

        delay(1)
        assertEquals(ConnectionSlot.RELAY, attempts.single().slot)

        val invalidatedAtMs = currentTime
        credentials.invalidate(ConnectionSlot.RELAY)
        delay(1)

        assertEquals(2, attempts.size)
        assertEquals(ConnectionSlot.RELAY, attempts[1].slot)
        // A deliberate reconnect, not a failure: it must not serve out a
        // backoff, and it must not have taught RelayHealth anything.
        assertEquals("reconnect must not wait out a backoff", invalidatedAtMs, attempts[1].atMs)
        assertFalse("re-pairing says nothing about relay reachability", health.isDown(currentTime))
        job.cancelAndJoin()
    }

    /** The other slot's credentials changing is none of this socket's
     *  business -- forgetting direct must not disturb a live relay socket. */
    @Test
    fun invalidatingTheOtherSlotLeavesTheLiveSocketAlone() = runTest {
        val credentials = SlotCredentials()
        val reconnector = SocketReconnector<Int>(RelayHealth(), now = { currentTime }, slotCredentials = credentials)
        val attempts = mutableListOf<Attempt>()
        val job = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                flow { awaitCancellation() }
            }) { true }
        }

        delay(1)
        assertEquals(ConnectionSlot.RELAY, attempts.single().slot)

        credentials.invalidate(ConnectionSlot.DIRECT)
        delay(1)

        assertEquals(1, attempts.size)
        job.cancelAndJoin()
    }

    /**
     * Same hazard [recoveryLandingDuringConnectIsNotMissed] covers, for the
     * other signal: a Forget landing while the socket is still opening must
     * not be swallowed, or the socket it raced would run on until it
     * happened to drop -- exactly the symptom cmux-app-2zn describes.
     */
    @Test
    fun invalidationLandingDuringConnectIsNotMissed() = runTest {
        val credentials = SlotCredentials()
        val reconnector = SocketReconnector<Int>(RelayHealth(), now = { currentTime }, slotCredentials = credentials)
        val attempts = mutableListOf<Attempt>()
        val job = launch {
            reconnector.run(openSocket = { slot ->
                attempts.add(Attempt(slot, currentTime))
                if (attempts.size == 1) credentials.invalidate(slot)
                flow { awaitCancellation() }
            }) { true }
        }

        delay(1)
        assertEquals(2, attempts.size)
        job.cancelAndJoin()
    }
}
