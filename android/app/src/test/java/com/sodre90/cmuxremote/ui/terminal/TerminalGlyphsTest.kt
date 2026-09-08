package com.sodre90.cmuxremote.ui.terminal

import androidx.compose.ui.graphics.Color
import com.sodre90.cmuxremote.model.Cell
import com.sodre90.cmuxremote.model.DecodedLine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Reads the bundled font's own cmap, so [GlyphSubstitutions] is checked against
 * the asset it exists for instead of against a measurement someone took by hand
 * once. Both halves of the table are silently wrong if that drifts: a key the
 * font can already draw replaces a correct glyph with an approximation, and a
 * value it cannot draw leaves the tofu exactly where it was.
 */
private class FontCoverage(file: File) {
    private val bytes = file.readBytes()

    private fun u8(o: Int) = bytes[o].toInt() and 0xFF
    private fun u16(o: Int) = (u8(o) shl 8) or u8(o + 1)
    private fun u32(o: Int) = (u16(o) shl 16) or u16(o + 2)

    private val cmap = (0 until u16(4))
        .map { 12 + 16 * it }
        .first { String(bytes, it, 4, Charsets.ISO_8859_1) == "cmap" }
        .let { u32(it + 8) }

    // Format 12 only: it is what this font ships and what covers the whole BMP
    // plus astral planes. A build that swaps in a font without one should fail
    // here rather than quietly answer "not covered" for every codepoint.
    private val format12 = (0 until u16(cmap + 2))
        .map { cmap + u32(cmap + 4 + 8 * it + 4) }
        .firstOrNull { u16(it) == 12 }
        ?: error("expected a format 12 cmap subtable in ${file.name}")

    fun covers(codepoint: Int): Boolean = (0 until u32(format12 + 12)).any { i ->
        val group = format12 + 16 + 12 * i
        val start = u32(group)
        codepoint in start..u32(group + 4) && u32(group + 8) + (codepoint - start) != 0
    }
}

class TerminalGlyphsTest {
    private val fonts = listOf("regular", "bold").map {
        FontCoverage(File("src/main/res/font/jetbrains_mono_nerd_$it.ttf"))
    }

    private fun eachFont(what: String, chars: Set<Char>, expectCovered: Boolean) {
        val wrong = fonts.flatMap { font ->
            chars.filter { font.covers(it.code) != expectCovered }
        }.toSortedSet()
        assertEquals(
            "$what: " + wrong.joinToString { "U+%04X %s".format(it.code, it) },
            emptySet<Char>(),
            wrong,
        )
    }

    @Test fun everySubstitutedCodepointIsOneTheFontGenuinelyCannotDraw() {
        eachFont("substituting glyphs the font already has", GlyphSubstitutions.keys, expectCovered = false)
    }

    @Test fun everySubstituteIsOneTheFontGenuinelyCanDraw() {
        eachFont("substituting in glyphs that are also tofu", GlyphSubstitutions.values.toSet(), expectCovered = true)
    }

    // A substitute that is itself substituted would depend on map iteration
    // order to resolve, and drawableGlyph only looks up once.
    @Test fun noSubstituteIsItselfSubstituted() {
        assertEquals(emptySet<Char>(), GlyphSubstitutions.values.intersect(GlyphSubstitutions.keys))
    }

    @Test fun ordinaryTextIsLeftAlone() {
        "abcXYZ0 9/-_$>█─✓●▶".forEach { assertEquals(it, drawableGlyph(it)) }
    }

    @Test fun theAuditedCodepointsAreAllCovered() {
        // The twelve found in 37,226 characters of live agent output, U+23F5
        // (the one cmux-app-68c was filed for) among them.
        val seenInAgentOutput = "▱▰⏺⎿⏵✻⇡✔✘※✽⏸"
        seenInAgentOutput.forEach {
            assertTrue("U+%04X %s still renders as tofu".format(it.code, it), drawableGlyph(it) != it)
        }
    }

    // -- the two places buildLine turns a cell into text

    private val colors = TerminalColors(
        background = Color(0xFF101010),
        foreground = Color(0xFFEEEEEE),
        cursor = Color(0xFFFF0000),
        selection = Color(0xFF333333),
    )

    private fun render(text: String, cursorColumn: Int?) =
        buildLine(DecodedLine(text.map { Cell(it, 0) }), emptyMap(), colors, cursorColumn).text

    @Test fun substitutesInsideAStyledRun() {
        assertEquals("███░░░", render("▰▰▰▱▱▱", cursorColumn = null))
    }

    @Test fun substitutesUnderTheCursorToo() {
        // The cursor cell is appended on its own path, which is exactly the kind
        // of second call site a fix like this gets applied to only once.
        assertEquals("●ok", render("⏺ok", cursorColumn = 0))
    }

    @Test fun substitutionPreservesColumnCount() {
        val row = "▰▱⏺⎿⏵✻⇡✔✘※✽⏸"
        assertEquals(row.length, render(row, cursorColumn = null).length)
    }
}
