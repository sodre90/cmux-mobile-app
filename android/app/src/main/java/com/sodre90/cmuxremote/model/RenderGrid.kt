package com.sodre90.cmuxremote.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull

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
)

object RenderGridDecoder {
    private const val BLANK = ' '

    /** Expand the sparse [grid] into a dense [DecodedGrid] (visible + scrollback). */
    fun decode(grid: RenderGrid): DecodedGrid {
        val cols = grid.columns.coerceAtLeast(0)
        val rowCount = grid.rows.coerceAtLeast(0)
        val lines = layout(grid.rowSpans, rowCount, cols)

        val sbRows = grid.scrollbackRows.coerceAtLeast(0)
        val scrollback =
            if (sbRows == 0 || grid.scrollbackSpans.isEmpty()) emptyList()
            else layout(grid.scrollbackSpans, sbRows, cols)

        return DecodedGrid(cols, rowCount, lines, grid.cursor, scrollback)
    }

    /** Lay [spans] onto a dense [rowCount] x [cols] grid of cells (width-1 chars). */
    private fun layout(spans: List<RowSpan>, rowCount: Int, cols: Int): List<DecodedLine> {
        val cells = Array(rowCount) { Array(cols) { Cell(BLANK, 0) } }
        for (span in spans) {
            if (span.row < 0 || span.row >= rowCount) continue
            var col = span.column
            for (ch in span.text) {
                if (col >= cols) break
                if (col >= 0) cells[span.row][col] = Cell(ch, span.styleId)
                col++
            }
        }
        return cells.map { DecodedLine(it.toList()) }
    }
}
