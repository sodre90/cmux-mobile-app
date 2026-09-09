package com.sodre90.cmuxremote.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SGR mouse reports a wheel-scrolling pane is driven with. Asserted as
 * literal byte strings rather than rebuilt from the constants that produce
 * them: these exact sequences were measured to move a live Claude pane, so a
 * test that shares their construction would not notice it drifting.
 */
class MouseReportTest {

    private val esc = Char(27)

    @Test fun draggingDownIsAWheelUpNotch() {
        // Direct manipulation, matching every other pane: drag DOWN to pull
        // earlier output into view.
        assertEquals(
            "$esc[<35;70;40M$esc[<64;70;40M",
            wheelNotch(up = true, column = 70, row = 40),
        )
    }

    @Test fun draggingUpIsAWheelDownNotch() {
        assertEquals(
            "$esc[<35;70;40M$esc[<65;70;40M",
            wheelNotch(up = false, column = 70, row = 40),
        )
    }

    @Test fun everyNotchCarriesItsOwnMotionReport() {
        // The pairing is what makes a notch count at all, and it has to survive
        // coalescing -- several notches sharing one write must still be several
        // motion+notch pairs, not one motion and a run of bare notches.
        val two = wheelNotch(true, 70, 40) + wheelNotch(true, 70, 40)
        assertEquals(2, Regex("$esc\\[<35;").findAll(two).count())
        assertEquals(2, Regex("$esc\\[<64;").findAll(two).count())
    }

    @Test fun everyReportUsesThePressTerminator() {
        // A wheel notch has no release, so the lowercase `m` form never applies.
        assertTrue(wheelNotch(up = true, column = 1, row = 1).endsWith("M"))
        assertTrue(wheelNotch(up = false, column = 1, row = 1).endsWith("M"))
    }

    @Test fun coordinatesAreEmittedVerbatim() {
        // Cells are 1-based on the wire; the caller converts, not this.
        assertEquals("$esc[<35;1;1M$esc[<64;1;1M", wheelNotch(up = true, column = 1, row = 1))
        assertEquals(
            "$esc[<35;140;79M$esc[<64;140;79M",
            wheelNotch(up = true, column = 140, row = 79),
        )
    }
}
