package com.sodre90.cmuxremote.ui.terminal

import com.sodre90.cmuxremote.model.RenderGrid
import com.sodre90.cmuxremote.model.RenderGridDecoder
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private val START = ESC + "[200~"
private val END = ESC + "[201~"

/**
 * Bracketed paste (cmux-app-ybb). A multi-line paste used to reach a shell or
 * an agent prompt as text plus newlines, so each line ran as it landed.
 */
class BracketedPasteTest {

    private fun modes(vararg json: String): List<JsonElement> =
        json.map { Json.parseToJsonElement(it) }

    private fun gridWith(vararg modeJson: String) =
        RenderGridDecoder.decode(RenderGrid(columns = 4, rows = 1, modes = modes(*modeJson)))

    // -- reading the mode off the grid

    @Test fun mode2004OnIsBracketedPaste() {
        assertTrue(gridWith("""{"ansi":false,"code":2004,"on":true}""").bracketedPaste)
    }

    @Test fun mode2004OffIsNot() {
        assertFalse(gridWith("""{"ansi":false,"code":2004,"on":false}""").bracketedPaste)
    }

    // 2004 is a DEC PRIVATE mode. An ANSI mode that happens to share the number
    // is a different mode entirely.
    @Test fun ansiMode2004IsADifferentModeAndDoesNotCount() {
        assertFalse(gridWith("""{"ansi":true,"code":2004,"on":true}""").bracketedPaste)
    }

    @Test fun aPaneReportingNoModesIsNot() {
        assertFalse(gridWith().bracketedPaste)
    }

    // The live shape: cmux sends the whole mode table, 2004 among 30 others.
    @Test fun findsTheModeAmongAllTheOthers() {
        val grid = gridWith(
            """{"ansi":false,"code":1,"on":false}""",
            """{"ansi":false,"code":1000,"on":false}""",
            """{"ansi":false,"code":2004,"on":true}""",
            """{"ansi":false,"code":2027,"on":true}""",
        )
        assertTrue(grid.bracketedPaste)
        assertFalse(grid.applicationCursorKeys)
        assertFalse(grid.mouseReporting)
    }

    // -- wrapping

    @Test fun wrapsThePasteWhenTheModeIsOn() {
        assertEquals(START + "one\ntwo" + END, bracketPaste("one\ntwo", enabled = true))
    }

    // A pane with bracketed paste off would receive the literal ESC[200~ as
    // input, which is worse than the problem being fixed.
    @Test fun sendsThePasteUnchangedWhenTheModeIsOff() {
        assertEquals("one\ntwo", bracketPaste("one\ntwo", enabled = false))
    }

    // Clipboard text carrying its own end marker would otherwise close the
    // bracket early and let the rest arrive as ordinary typed input -- exactly
    // the failure bracketing exists to prevent.
    @Test fun stripsAnEndMarkerHidingInTheClipboard() {
        val wrapped = bracketPaste("rm -rf /${END}echo pwned", enabled = true)

        assertEquals(START + "rm -rf /echo pwned" + END, wrapped)
        assertEquals("exactly one end marker", 1, wrapped.windowed(END.length).count { it == END })
    }

    @Test fun anEmptyPasteIsStillWellFormed() {
        assertEquals(START + END, bracketPaste("", enabled = true))
    }
}
