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
        val s = Style(id = 1, foreground = JsonPrimitive("#00ff00"), background = JsonPrimitive("#112233"), inverse = true)
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
        assertTrue(r.bold); assertTrue(r.italic); assertTrue(r.underline); assertTrue(r.strikethrough)
    }

    @Test fun parsesHexColors() {
        assertEquals(Color(0xFF00FF00), parseColor("#00ff00"))
        assertEquals(null, parseColor("blue"))
        assertEquals(null, parseColor(null))
    }
}
