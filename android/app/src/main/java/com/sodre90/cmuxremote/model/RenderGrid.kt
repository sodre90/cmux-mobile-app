package com.sodre90.cmuxremote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/**
 * The `cmux.render-grid.v1` terminal snapshot: a sparse, style-run encoding of
 * the visible screen rather than raw PTY bytes. Reconstruct a dense grid with
 * [RenderGridDecoder.decode].
 */
@Serializable
data class RenderGrid(
    val format: String? = null,
    val columns: Int = 0,
    val rows: Int = 0,
    val cursor: Cursor? = null,
    // cmux sends modes as objects ({"ansi":bool,"code":int,"on":bool}); kept as
    // raw JSON since rendering never reads them. A wrong value type here aborts
    // the whole frame parse, so stay tolerant.
    val modes: List<JsonElement> = emptyList(),
    @SerialName("row_spans") val rowSpans: List<RowSpan> = emptyList(),
    @SerialName("scrollback_spans") val scrollbackSpans: List<RowSpan> = emptyList(),
    val styles: List<Style> = emptyList(),
    @SerialName("scrollback_rows") val scrollbackRows: Int = 0,
    @SerialName("active_screen") val activeScreen: String? = null,
    val full: Boolean = false,
    @SerialName("state_seq") val stateSeq: Long = 0L,
)

@Serializable
data class Cursor(
    val row: Int = 0,
    val column: Int = 0,
    val style: String? = null,
    val visible: Boolean = true,
    val blinking: Boolean = false,
)

/** One style-run of text on a row, starting at [column]. */
@Serializable
data class RowSpan(
    val row: Int = 0,
    val column: Int = 0,
    @SerialName("cell_width") val cellWidth: Int = 0,
    @SerialName("style_id") val styleId: Int = 0,
    val text: String = "",
)

/**
 * A referenced style. Colors are kept as raw JSON because cmux may encode them
 * as hex/name strings or palette ints depending on the source; [foregroundString]
 * / [backgroundString] extract a string form when present.
 */
@Serializable
data class Style(
    val id: Int = 0,
    val foreground: JsonElement? = null,
    val background: JsonElement? = null,
    val bold: Boolean = false,
    val faint: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val inverse: Boolean = false,
    val strikethrough: Boolean = false,
) {
    val foregroundString: String? get() = foreground.asStringOrNull()
    val backgroundString: String? get() = background.asStringOrNull()
}

private fun JsonElement?.asStringOrNull(): String? =
    (this as? JsonPrimitive)?.contentOrNull

/** A single reconstructed terminal cell. */
data class Cell(val char: Char, val styleId: Int)

/** A reconstructed terminal row, exactly [DecodedGrid.columns] cells wide. */
data class DecodedLine(val cells: List<Cell>) {
    val text: String get() = buildString { cells.forEach { append(it.char) } }
}

/** A dense, render-ready grid produced from a [RenderGrid]. */
data class DecodedGrid(
    val columns: Int,
    val rows: Int,
    val lines: List<DecodedLine>,
    val cursor: Cursor?,
    val scrollbackLines: List<DecodedLine> = emptyList(),
    // DECCKM state, so the key bar can send the right arrow/Home/End encoding.
    val applicationCursorKeys: Boolean = false,
)

/**
 * True when DECCKM (DEC private mode 1, "application cursor keys") is set: arrow
 * and Home/End keys must then be sent as SS3 (`ESC O x`) rather than CSI
 * (`ESC [ x`). cmux reports each mode as `{"ansi":bool,"code":int,"on":bool}`,
 * where a DEC-private mode carries `ansi=false`; we want mode 1 present and on.
 */
internal fun applicationCursorKeysEnabled(modes: List<JsonElement>): Boolean =
    modes.any { element ->
        val obj = element as? JsonObject ?: return@any false
        val ansi = (obj["ansi"] as? JsonPrimitive)?.booleanOrNull ?: false
        val code = (obj["code"] as? JsonPrimitive)?.intOrNull
        val on = (obj["on"] as? JsonPrimitive)?.booleanOrNull ?: false
        !ansi && code == 1 && on
    }

object RenderGridDecoder {
    private const val BLANK = ' '

    /** Expand the sparse [grid] into a dense [DecodedGrid] (visible + scrollback). */
    fun decode(grid: RenderGrid): DecodedGrid {
        val cols = grid.columns.coerceAtLeast(0)
        val rowCount = grid.rows.coerceAtLeast(0)
        val lines = layout(grid.rowSpans, rowCount, cols)

        val sbRows = grid.scrollbackRows.coerceAtLeast(0)
        val scrollback =
            if (sbRows == 0 || grid.scrollbackSpans.isEmpty()) {
                emptyList()
            } else {
                layout(grid.scrollbackSpans, sbRows, cols)
            }

        return DecodedGrid(
            cols,
            rowCount,
            lines,
            grid.cursor,
            scrollback,
            applicationCursorKeys = applicationCursorKeysEnabled(grid.modes),
        )
    }

    /**
     * Lay [spans] onto a dense [rowCount] x [cols] grid of cells (width-1 chars).
     *
     * A span's `cell_width` is the number of terminal columns its whole `text`
     * run occupies; cmux only groups multiple characters into one span when
     * they're all narrow (width 1), so `cell_width` is only meaningful as a
     * per-character width when the span carries exactly one Unicode codepoint —
     * that single glyph may be a wide CJK/emoji character (2 columns) or an
     * astral one needing a UTF-16 surrogate pair (2 `Char`s). Iterating by
     * codepoint (not `Char`) keeps a surrogate pair intact and in order; any
     * extra declared width beyond what the codepoint's `Char`s need is a blank
     * filler cell, so a later span's declared `column` still lines up.
     */
    private fun layout(spans: List<RowSpan>, rowCount: Int, cols: Int): List<DecodedLine> {
        val cells = Array(rowCount) { Array(cols) { Cell(BLANK, 0) } }
        for (span in spans) {
            if (span.row < 0 || span.row >= rowCount) continue
            val codepoints = span.text.codePoints().toArray()
            val singleWidth = if (codepoints.size == 1) span.cellWidth.coerceAtLeast(1) else 1
            var col = span.column
            for (cp in codepoints) {
                if (col >= cols) break
                val chars = Character.toChars(cp)
                // Never fewer columns than the codepoint needs Chars for, even if
                // cell_width under-declares it (e.g. a mis-tagged narrow-astral char).
                val width = maxOf(singleWidth, chars.size)
                if (col >= 0) {
                    for (i in chars.indices) {
                        val c = col + i
                        if (c < cols) cells[span.row][c] = Cell(chars[i], span.styleId)
                    }
                    for (c in (col + chars.size) until (col + width).coerceAtMost(cols)) {
                        cells[span.row][c] = Cell(BLANK, span.styleId)
                    }
                }
                col += width
            }
        }
        return cells.map { DecodedLine(it.toList()) }
    }
}
