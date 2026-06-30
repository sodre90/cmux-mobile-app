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

/** Maps an accumulated pinch [scale] onto a clamped font size in sp. */
fun zoomedFontSizeSp(baseSp: Float, scale: Float, min: Float = 7f, max: Float = 22f): Float =
    (baseSp * scale).coerceIn(min, max)
