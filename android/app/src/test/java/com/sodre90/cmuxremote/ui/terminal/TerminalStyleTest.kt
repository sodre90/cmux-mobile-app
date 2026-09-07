package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.ui.graphics.Color
import com.sodre90.cmuxremote.model.Style
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TerminalStyleTest {
    private val colors = TerminalColors(
        background = Color(0xFF000000),
        foreground = Color(0xFFFFFFFF),
        cursor = Color(0xFFFF0000),
        selection = Color(0xFF333333),
    )

    @Test fun usesStyleColors() {
        val s = Style(id = 1, foreground = JsonPrimitive("#00ff00"), background = JsonPrimitive("#112233"))
        val r = resolveSpan(s, colors)
        assertEquals(Color(0xFF00FF00), r.fg)
        assertEquals(Color(0xFF112233), r.bg)
    }

    @Test fun fallsBackToThemeWhenNoColor() {
        val r = resolveSpan(Style(id = 0), colors)
        assertEquals(colors.foreground, r.fg)
        assertEquals(colors.background, r.bg)
    }

    @Test fun inverseSwapsForegroundAndBackground() {
        val s = Style(
            id = 1,
            foreground = JsonPrimitive("#00ff00"),
            background = JsonPrimitive("#112233"),
            inverse = true
        )
        val r = resolveSpan(s, colors)
        assertEquals(Color(0xFF112233), r.fg)
        assertEquals(Color(0xFF00FF00), r.bg)
    }

    @Test fun faintReducesForegroundAlpha() {
        val s = Style(id = 1, foreground = JsonPrimitive("#ffffff"), faint = true)
        val r = resolveSpan(s, colors)
        assertEquals(0.6f, r.fg.alpha, 0.001f)
    }

    @Test fun carriesDecorationFlags() {
        val s = Style(id = 1, bold = true, italic = true, underline = true, strikethrough = true)
        val r = resolveSpan(s, colors)
        assertTrue(r.bold)
        assertTrue(r.italic)
        assertTrue(r.underline)
        assertTrue(r.strikethrough)
    }

    // -- the grid's default background (cmux-app-4x2) --

    private val canvas = TerminalColors(
        background = Color(0xFF1E1E2E),
        foreground = Color(0xFFFFFFFF),
        cursor = Color(0xFFFF0000),
        selection = Color(0xFF333333),
    )

    @Test fun theGridsDefaultBackgroundYieldsToTheCanvas() {
        // cmux states the default as its own palette black; painting it literally
        // tiled a black box behind every run on the #1E1E2E ground.
        val s = Style(id = 0, background = JsonPrimitive("#000000"))
        assertEquals(canvas.background, resolveSpan(s, canvas, defaultBackground = "#000000").bg)
    }

    @Test fun aGenuinelyStyledBackgroundStillPaints() {
        val s = Style(id = 3, background = JsonPrimitive("#112233"))
        assertEquals(Color(0xFF112233), resolveSpan(s, canvas, defaultBackground = "#000000").bg)
    }

    @Test fun anExplicitBlackThatIsNotTheDefaultStillPaints() {
        // If the grid's default were something else, black is a real choice.
        val s = Style(id = 3, background = JsonPrimitive("#000000"))
        assertEquals(Color(0xFF000000), resolveSpan(s, canvas, defaultBackground = "#1E1E2E").bg)
    }

    @Test fun invertingADefaultCellSwapsAgainstTheCanvas() {
        val s = Style(
            id = 0,
            foreground = JsonPrimitive("#00ff00"),
            background = JsonPrimitive("#000000"),
            inverse = true,
        )
        val r = resolveSpan(s, canvas, defaultBackground = "#000000")
        assertEquals(canvas.background, r.fg)
        assertEquals(Color(0xFF00FF00), r.bg)
    }

    @Test fun defaultBackgroundIsReadFromStyleZero() {
        val styles = mapOf(0 to Style(id = 0, background = JsonPrimitive("#000000")), 3 to Style(id = 3))
        assertEquals("#000000", defaultBackgroundOf(styles))
        assertEquals(null, defaultBackgroundOf(mapOf(3 to Style(id = 3))))
    }

    @Test fun omittingTheDefaultKeepsTheOldBehaviour() {
        // Existing call sites that pass no default must resolve exactly as before.
        val s = Style(id = 0, background = JsonPrimitive("#000000"))
        assertEquals(Color(0xFF000000), resolveSpan(s, canvas).bg)
    }

    @Test fun parsesHexColors() {
        assertEquals(Color(0xFF00FF00), parseColor("#00ff00"))
        assertEquals(null, parseColor("blue"))
        assertEquals(null, parseColor(null))
    }
}
