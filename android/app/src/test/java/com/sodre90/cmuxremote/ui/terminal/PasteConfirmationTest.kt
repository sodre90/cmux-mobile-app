package com.sodre90.cmuxremote.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The line between a paste that just goes through and one worth a dialog. Get
 * it wrong in one direction and a multi-line clipboard runs as commands with
 * nothing shown first; wrong in the other and pasting a branch name costs a tap.
 */
class PasteConfirmationTest {

    @Test
    fun aShortSingleLineGoesStraightThrough() {
        assertFalse(needsPasteConfirmation("feature/pull-to-refresh"))
    }

    @Test
    fun aTrailingNewlineAloneDoesNotMakeItMultiLine() {
        // Copying one line out of an editor usually brings its terminator along;
        // that is still one command, so it should not raise a dialog.
        assertFalse(needsPasteConfirmation("git status\n"))
        assertEquals(1, pasteLineCount("git status\n"))
    }

    @Test
    fun anEmbeddedNewlineNeedsConfirmation() {
        assertTrue(needsPasteConfirmation("git add -A\ngit commit\n"))
        assertEquals(2, pasteLineCount("git add -A\ngit commit\n"))
    }

    @Test
    fun aLongSingleLineNeedsConfirmation() {
        assertFalse(needsPasteConfirmation("x".repeat(200)))
        assertTrue(needsPasteConfirmation("x".repeat(201)))
    }

    @Test
    fun blankLinesInsideThePasteAreCounted() {
        assertEquals(3, pasteLineCount("one\n\nthree"))
    }
}
