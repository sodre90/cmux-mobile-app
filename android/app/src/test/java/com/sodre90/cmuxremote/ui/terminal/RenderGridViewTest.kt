package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.ui.graphics.Color
import com.sodre90.cmuxremote.model.Cell
import com.sodre90.cmuxremote.model.DecodedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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

    @Test fun cappedScrollbackKeepsShortHistoryUntouched() {
        val lines = (1..5).map { DecodedLine(listOf(Cell('a' + it, 0))) }
        assertEquals(lines, cappedScrollback(lines, max = 10))
    }

    @Test fun cappedScrollbackDropsOldestBeyondMax() {
        val lines = (0 until 10).map { i -> DecodedLine(listOf(Cell('0' + i, 0))) }
        val capped = cappedScrollback(lines, max = 3)
        assertEquals(3, capped.size)
        assertEquals(lines.takeLast(3), capped)
    }

    @Test fun sticksToBottomOnlyWhenNearPreviousBottomAndNotUserScrolling() {
        // At (or within tolerance of) the previous bottom, hands off: pin.
        assertTrue(shouldStickToBottom(currentValue = 996, previousMax = 1000, userScrolling = false))
        assertTrue(shouldStickToBottom(currentValue = 1000, previousMax = 1000, userScrolling = false))
        assertTrue(shouldStickToBottom(currentValue = 0, previousMax = 0, userScrolling = false))

        // Scrolled up beyond the tolerance: leave the position alone.
        assertFalse(shouldStickToBottom(currentValue = 500, previousMax = 1000, userScrolling = false))
        assertFalse(shouldStickToBottom(currentValue = 990, previousMax = 1000, userScrolling = false))

        // The regression this guards: a streaming pane restarts the effect every
        // frame; even "at the bottom" must NOT yank while a drag/fling is live.
        assertFalse(shouldStickToBottom(currentValue = 1000, previousMax = 1000, userScrolling = true))

        // First frame after composition: prevMax=0, and a live gesture still wins.
        assertFalse(shouldStickToBottom(currentValue = 0, previousMax = 0, userScrolling = true))
    }

    // RenderGridView's per-row `remember(rendered, styleMap, colors, cur)` key relies on
    // this: wrapModeLine/trimTrailingBlanks allocate a new DecodedLine (via subList/
    // filterNot) on every recomposition, even when a row's content hasn't changed since
    // the last frame. That's only safe to skip rebuilding the AnnotatedString for if two
    // independently-built, content-identical DecodedLines still compare equal -- proven
    // here directly, rather than assumed, since it's the crux of whether the guide's
    // "fresh allocation defeats memoization" claim holds.
    @Test fun wrapModeProducesStructurallyEqualButFreshLines() {
        val frameOne = DecodedLine(listOf(Cell('a', 0), Cell('b', 0), Cell(' ', 0), Cell(' ', 0)))
        val frameTwo = DecodedLine(listOf(Cell('a', 0), Cell('b', 0), Cell(' ', 0), Cell(' ', 0)))

        val renderedOne = wrapModeLine(frameOne, cursorColumn = null)
        val renderedTwo = wrapModeLine(frameTwo, cursorColumn = null)

        assertEquals(renderedOne, renderedTwo) // same content -> remember() sees "unchanged"
        assertNotSame(renderedOne, renderedTwo) // yet genuinely different, freshly-allocated instances
    }
}
