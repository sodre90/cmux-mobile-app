package com.sodre90.cmuxremote.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalInputDiffTest {
    private val del = Char(127).toString()

    @Test fun pureAppendSendsOnlyTheNewSuffix() =
        assertEquals("o", diffToKeystrokes("hel", "helo"))

    @Test fun typingFromEmptySendsTheWholeString() =
        assertEquals("ls", diffToKeystrokes("", "ls"))

    @Test fun trailingBackspaceSendsOneDelPerErasedChar() =
        assertEquals(del, diffToKeystrokes("ls", "l"))

    @Test fun clearingTheFieldSendsOneDelPerChar() =
        assertEquals(del.repeat(2), diffToKeystrokes("ls", ""))

    @Test fun noChangeSendsNothing() =
        assertEquals("", diffToKeystrokes("ls", "ls"))

    @Test fun midStringEditBackspacesToTheDivergencePointThenRetypes() =
        // "helXo" -> "helYo": shared prefix "hel", then old has "Xo" left (2
        // erases) before retyping new's remaining suffix "Yo".
        assertEquals(del + del + "Yo", diffToKeystrokes("helXo", "helYo"))
}
