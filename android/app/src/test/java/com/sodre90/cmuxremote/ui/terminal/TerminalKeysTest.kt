package com.sodre90.cmuxremote.ui.terminal

import org.junit.Assert.assertEquals
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
        assertEquals(esc + "[A", key("↑").sequence(applicationCursorKeys = false))
    @Test fun arrowUpIsSs3WhenApplication() =
        assertEquals(esc + "OA", key("↑").sequence(applicationCursorKeys = true))
    @Test fun homeIsCsiWhenNormal() =
        assertEquals(esc + "[H", key("Home").sequence(applicationCursorKeys = false))
    @Test fun homeIsSs3WhenApplication() =
        assertEquals(esc + "OH", key("Home").sequence(applicationCursorKeys = true))
    @Test fun endIsSs3WhenApplication() =
        assertEquals(esc + "OF", key("End").sequence(applicationCursorKeys = true))

    @Test fun enterKeyIsPresent() = assertTrue(TerminalKeys.any { it.label == "⏎" })
}
