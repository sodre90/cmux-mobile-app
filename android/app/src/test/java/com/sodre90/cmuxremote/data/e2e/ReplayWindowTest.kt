package com.sodre90.cmuxremote.data.e2e

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ReplayWindowTest {

    @Test
    fun freshWindowAcceptsAnything() {
        val w = ReplayWindow()
        assertTrue(w.canAccept(0L))
        assertTrue(w.canAccept(1000L))
    }

    @Test
    fun inOrderAcceptThenRejectExactReplay() {
        var w = ReplayWindow()
        assertTrue(w.canAccept(5L))
        w = w.commit(5L)
        assertFalse(w.canAccept(5L)) // exact replay
        assertTrue(w.canAccept(6L)) // new high still fine
    }

    @Test
    fun outOfOrderWithinWindowIsAccepted() {
        var w = ReplayWindow()
        w = w.commit(10L)
        assertTrue(w.canAccept(7L)) // arrived late, within window, never seen
        w = w.commit(7L)
        assertFalse(w.canAccept(7L)) // now a replay
        assertEquals(10L, w.highestSeen) // committing a lower n doesn't move the high-water mark
    }

    @Test
    fun tooOldOutsideWindowIsRejected() {
        var w = ReplayWindow()
        w = w.commit(1000L)
        assertFalse(w.canAccept(1000L - 64L)) // exactly at the boundary: too old
        assertTrue(w.canAccept(1000L - 63L)) // one inside the boundary: still fine
    }

    @Test
    fun windowSlidesForwardAsNewHighsArrive() {
        var w = ReplayWindow()
        w = w.commit(100L)
        w = w.commit(50L) // accepted, within window at the time
        w = w.commit(200L) // big jump forward -- window re-centers on 200
        assertFalse(w.canAccept(50L)) // now outside the new window (200-50=150 > 64)
        assertTrue(w.canAccept(199L)) // still inside the new window
    }

    @Test
    fun replayAfterWindowSlidePastItIsStillRejectedAsExactMatch() {
        // A counter committed just before a big forward jump, then replayed
        // immediately after the jump but still within the new window, must
        // still be caught as a replay, not silently accepted as "old but new."
        var w = ReplayWindow()
        w = w.commit(100L)
        w = w.commit(101L) // small jump; 100 still within window (101-100=1)
        assertFalse(w.canAccept(100L))
    }

    @Test
    fun negativeCounterIsRejectedNotTreatedAsFreshWindow() {
        // Protocol counters are always non-negative. `highestSeen < 0` is the
        // internal "no history yet" sentinel -- without this guard, a
        // negative n reaching canAccept on a fresh window would exploit that
        // same check and be silently accepted instead of rejected, and if
        // ever committed would leave highestSeen permanently negative,
        // disabling replay protection for that state forever after.
        val w = ReplayWindow()
        assertFalse(w.canAccept(-1L))
    }

    @Test(expected = IllegalArgumentException::class)
    fun commitRejectsNegativeCounter() {
        ReplayWindow().commit(-1L)
    }
}
