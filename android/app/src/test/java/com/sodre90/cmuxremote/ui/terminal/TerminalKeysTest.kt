package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.ui.input.key.Key
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalKeysTest {
    private val esc = Char(27).toString()
    private fun key(label: String) = TerminalKeys.first { it.label == label }

    // Static keys are independent of cursor-key mode.
    @Test fun ctrlCIsEtx() =
        assertEquals(Char(3).toString(), key("^C").sequence(applicationCursorKeys = false))

    @Test fun enterIsCarriageReturn() =
        assertEquals(Char(13).toString(), key("⏎").sequence(applicationCursorKeys = false))

    @Test fun pageUpIsCsi5Tilde() =
        assertEquals(esc + "[5~", key("PgUp").sequence(applicationCursorKeys = true))

    // Cursor keys: CSI when DECCKM is reset, SS3 when it is set.
    @Test fun arrowUpIsCsiWhenNormal() =
        assertEquals(esc + "[A", ArrowUp.sequence(applicationCursorKeys = false))

    @Test fun arrowUpIsSs3WhenApplication() =
        assertEquals(esc + "OA", ArrowUp.sequence(applicationCursorKeys = true))

    @Test fun arrowLeftIsSs3WhenApplication() =
        assertEquals(esc + "OD", ArrowLeft.sequence(applicationCursorKeys = true))

    @Test fun homeIsCsiWhenNormal() =
        assertEquals(esc + "[H", key("Home").sequence(applicationCursorKeys = false))

    @Test fun homeIsSs3WhenApplication() =
        assertEquals(esc + "OH", key("Home").sequence(applicationCursorKeys = true))

    @Test fun endIsSs3WhenApplication() =
        assertEquals(esc + "OF", key("End").sequence(applicationCursorKeys = true))

    @Test fun enterKeyIsPresent() = assertTrue(TerminalKeys.any { it.label == "⏎" })

    // Arrows are surfaced via the D-pad, not the scrollable bar.
    @Test fun arrowsNotInScrollableBar() =
        assertTrue(TerminalKeys.none { it.label == "↑" || it.label == "→" })

    // physicalKeySequence: physical/Bluetooth-keyboard key handling.
    @Test fun physicalArrowUpIsSs3WhenApplication() =
        assertEquals(esc + "OA", physicalKeySequence(Key.DirectionUp, applicationCursorKeys = true))

    @Test fun physicalArrowLeftIsCsiWhenNormal() =
        assertEquals(esc + "[D", physicalKeySequence(Key.DirectionLeft, applicationCursorKeys = false))

    @Test fun physicalEscapeIsEsc() =
        assertEquals(esc, physicalKeySequence(Key.Escape, applicationCursorKeys = false))

    @Test fun physicalTabIsTabChar() =
        assertEquals("\t", physicalKeySequence(Key.Tab, applicationCursorKeys = false))

    @Test fun physicalEnterIsCarriageReturn() =
        assertEquals(Char(13).toString(), physicalKeySequence(Key.Enter, applicationCursorKeys = false))

    @Test fun physicalNumPadEnterIsCarriageReturn() =
        assertEquals(Char(13).toString(), physicalKeySequence(Key.NumPadEnter, applicationCursorKeys = false))

    @Test fun physicalRegularCharKeyIsUnhandled() =
        assertNull(physicalKeySequence(Key.A, applicationCursorKeys = false))

    // ctrlSequence: the Ctrl chip's letter -> control-byte mapping.
    @Test fun ctrlSequenceAIsSoh() = assertEquals(Char(1).toString(), ctrlSequence('A'))

    @Test fun ctrlSequenceLIsFormFeed() = assertEquals(Char(12).toString(), ctrlSequence('L'))

    @Test fun ctrlSequenceRIsDc2() = assertEquals(Char(18).toString(), ctrlSequence('R'))

    @Test fun ctrlSequenceIsCaseInsensitive() = assertEquals(ctrlSequence('a'), ctrlSequence('A'))

    @Test fun ctrlSequenceRejectsNonLetter() = assertNull(ctrlSequence('1'))

    @Test fun ctrlSequenceRejectsSpace() = assertNull(ctrlSequence(' '))

    // ctrlSequence must match the quick buttons byte-for-byte -- the chip is
    // meant to be an equivalent, general path to the same three combos, not a
    // parallel implementation that could drift from them.
    @Test fun ctrlSequenceCMatchesQuickButton() =
        assertEquals(key("^C").sequence(applicationCursorKeys = false), ctrlSequence('C'))

    @Test fun ctrlSequenceDMatchesQuickButton() =
        assertEquals(key("^D").sequence(applicationCursorKeys = false), ctrlSequence('D'))

    @Test fun ctrlSequenceZMatchesQuickButton() =
        assertEquals(key("^Z").sequence(applicationCursorKeys = false), ctrlSequence('Z'))

    // applyCtrlArm: what the key bar's latching Ctrl chip actually applies to
    // outbound text -- single letters are rewritten, everything else passes
    // through unchanged (multi-character text has no single Ctrl byte, and
    // non-letters have no Ctrl mapping here).
    @Test fun applyCtrlArmRewritesSingleLetter() = assertEquals(Char(12).toString(), applyCtrlArm("l"))

    @Test fun applyCtrlArmLeavesMultiCharUnchanged() = assertEquals("ab", applyCtrlArm("ab"))

    @Test fun applyCtrlArmLeavesNonLetterUnchanged() = assertEquals("5", applyCtrlArm("5"))

    @Test fun applyCtrlArmLeavesEmptyUnchanged() = assertEquals("", applyCtrlArm(""))

    // Byte-identical to the ^C/^D/^Z quick buttons for the combos they overlap on.
    @Test fun applyCtrlArmCMatchesQuickButton() =
        assertEquals(key("^C").sequence(applicationCursorKeys = false), applyCtrlArm("c"))

    @Test fun applyCtrlArmDMatchesQuickButton() =
        assertEquals(key("^D").sequence(applicationCursorKeys = false), applyCtrlArm("d"))

    @Test fun applyCtrlArmZMatchesQuickButton() =
        assertEquals(key("^Z").sequence(applicationCursorKeys = false), applyCtrlArm("z"))
}
