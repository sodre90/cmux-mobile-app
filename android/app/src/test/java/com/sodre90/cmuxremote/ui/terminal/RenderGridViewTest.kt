package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.ui.graphics.Color
import com.sodre90.cmuxremote.model.Cell
import com.sodre90.cmuxremote.model.DecodedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
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

    @Test fun trimDropsTrailingDefaultBlanks() {
        val l = DecodedLine(listOf(Cell('a', 0), Cell('b', 0), Cell(' ', 0), Cell(' ', 0)))
        assertEquals("ab", trimTrailingBlanks(l, cursorColumn = null).text)
    }

    @Test fun trimKeepsInteriorBlanks() {
        val l = DecodedLine(listOf(Cell('a', 0), Cell(' ', 0), Cell('b', 0), Cell(' ', 0)))
        assertEquals("a b", trimTrailingBlanks(l, cursorColumn = null).text)
    }

    @Test fun trimKeepsStyledTrailingSpace() {
        // A trailing space carrying a non-default style (e.g. a colored bar) stays.
        val l = DecodedLine(listOf(Cell('a', 0), Cell(' ', 7)))
        assertEquals(2, trimTrailingBlanks(l, cursorColumn = null).cells.size)
    }

    @Test fun trimNeverTrimsPastCursor() {
        val l = DecodedLine(listOf(Cell('a', 0), Cell(' ', 0), Cell(' ', 0)))
        assertEquals(3, trimTrailingBlanks(l, cursorColumn = 2).cells.size)
    }

    @Test fun trimCollapsesFullyBlankLine() {
        val l = DecodedLine(listOf(Cell(' ', 0), Cell(' ', 0)))
        assertEquals("", trimTrailingBlanks(l, cursorColumn = null).text)
    }

    @Test fun ruleDetectsBoxBorderRow() {
        val l = DecodedLine("╭────────╮".map { Cell(it, 0) })
        assertEquals(true, isHorizontalRule(l))
    }

    @Test fun ruleRejectsTextRow() {
        val l = DecodedLine("│ hi │".map { Cell(it, 0) })
        assertEquals(false, isHorizontalRule(l))
    }

    @Test fun ruleRejectsBlankRow() {
        val l = DecodedLine("   ".map { Cell(it, 0) })
        assertEquals(false, isHorizontalRule(l))
    }

    @Test fun wrapDropsPureBorderRow() {
        val l = DecodedLine("──────────".map { Cell(it, 0) })
        assertNull(wrapModeLine(l, cursorColumn = null))
    }

    @Test fun wrapStripsTitledSeparatorToLabel() {
        val l = DecodedLine("──────── Title ────────".map { Cell(it, 0) })
        assertEquals("Title", wrapModeLine(l, cursorColumn = null)!!.text.trim())
    }

    @Test fun wrapKeepsListBulletDash() {
        // A short dash run (a bullet) is content, not decoration — keep it.
        val l = DecodedLine("- a b".map { Cell(it, 0) })
        assertEquals("- a b", wrapModeLine(l, cursorColumn = null)!!.text)
    }

    @Test fun wrapNeverDropsCursorRow() {
        val l = DecodedLine("──────────".map { Cell(it, 0) })
        assertNotNull(wrapModeLine(l, cursorColumn = 2))
    }
}
