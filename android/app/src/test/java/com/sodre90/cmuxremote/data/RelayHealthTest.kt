package com.sodre90.cmuxremote.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RelayHealthTest {

    @Test
    fun freshInstanceAssumesRelayIsHealthy() {
        assertFalse(RelayHealth().isDown(now = 0L))
    }

    @Test
    fun penaltyWindowExpiresOnItsOwn() {
        val health = RelayHealth(penaltyMs = 100L)
        health.markDown(now = 1_000L)
        assertTrue(health.isDown(now = 1_099L))
        assertFalse(health.isDown(now = 1_100L))
    }

    @Test
    fun markUpClearsAnOutstandingPenaltyImmediately() {
        val health = RelayHealth(penaltyMs = 10_000L)
        health.markDown(now = 1_000L)
        assertTrue(health.isDown(now = 1_500L))

        health.markUp()
        assertFalse(health.isDown(now = 1_500L))
    }

    /**
     * Only the down-to-up edge advances the counter: a steady stream of
     * successful relay calls must not look like repeated recoveries, or every
     * call would kick a DIRECT socket that has already come back.
     */
    @Test
    fun recoveriesAdvanceOnlyOnTheDownToUpEdge() {
        val health = RelayHealth()
        assertEquals(0L, health.recoveries.value)

        health.markUp()
        assertEquals("no penalty outstanding, nothing to recover from", 0L, health.recoveries.value)

        health.markDown(now = 0L)
        assertEquals("going down is not a recovery", 0L, health.recoveries.value)

        health.markUp()
        assertEquals(1L, health.recoveries.value)

        repeat(5) { health.markUp() }
        assertEquals("already up, so still one recovery", 1L, health.recoveries.value)

        health.markDown(now = 0L)
        health.markUp()
        assertEquals(2L, health.recoveries.value)
    }
}
