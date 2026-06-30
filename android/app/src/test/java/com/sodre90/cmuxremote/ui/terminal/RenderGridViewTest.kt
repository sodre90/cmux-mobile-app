package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.ui.graphics.Color
import com.sodre90.cmuxremote.model.Cell
import com.sodre90.cmuxremote.model.DecodedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class RenderGridViewTest {
    private val colors = TerminalColors(
        background = Color(0xFF101010),
        foreground = Color(0xFFEEEEEE),
        cursor = Color(0xFFFF0000),
        selection = Color(0xFF333333),
    )
    private val line = DecodedLine(listOf(Cell('a', 0), Cell('b', 0), Cell('c', 0)))

    @Test fun preservesLineText() {
        val s = buildLine(line, emptyMap(), colors, cursorColumn = null)
        assertEquals("abc", s.text)
    }

    @Test fun cursorCellUsesCursorBackground() {
        val s = buildLine(line, emptyMap(), colors, cursorColumn = 1)
        val span = s.spanStyles.firstOrNull { it.start == 1 && it.end == 2 }
        assertNotNull(span)
        assertEquals(colors.cursor, span!!.item.background)
        assertEquals(colors.background, span.item.color)
    }
}
