package com.sodre90.cmuxremote.ui.terminal

import kotlin.math.floor

/** Visible columns/rows that fit a viewport given a measured monospace cell. */
fun gridDimensions(
    widthPx: Float,
    heightPx: Float,
    cellWidthPx: Float,
    cellHeightPx: Float,
    minCols: Int = 20,
    maxCols: Int = 400,
    minRows: Int = 5,
    maxRows: Int = 200,
): Pair<Int, Int> {
    if (cellWidthPx <= 0f || cellHeightPx <= 0f) return minCols to minRows
    val cols = floor(widthPx / cellWidthPx).toInt().coerceIn(minCols, maxCols)
    val rows = floor(heightPx / cellHeightPx).toInt().coerceIn(minRows, maxRows)
    return cols to rows
}

/**
 * Largest font size (sp) at which a [gridCols]-wide monospace line still fits
 * [availWidthPx], given the glyph advance [advancePerSp] (px of advance per 1 sp
 * of font size). This is the fit-to-width baseline: at the returned size the whole
 * surface width is visible without horizontal scrolling.
 *
 * Clamped to [min] (legibility floor — on very wide output the true fit would be
 * illegibly tiny, so we stop here and let horizontal panning cover the overflow)
 * and [max] (so a narrow surface does not blow the font up absurdly large).
 */
fun fitFontSizeSp(
    availWidthPx: Float,
    gridCols: Int,
    advancePerSp: Float,
    min: Float = 7f,
    max: Float = 22f,
): Float {
    if (gridCols <= 0 || advancePerSp <= 0f || availWidthPx <= 0f) return max
    return (availWidthPx / (gridCols * advancePerSp)).coerceIn(min, max)
}
