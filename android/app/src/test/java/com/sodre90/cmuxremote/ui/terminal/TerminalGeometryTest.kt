package com.sodre90.cmuxremote.ui.terminal

import org.junit.Assert.assertEquals
import org.junit.Test

class TerminalGeometryTest {
    @Test fun fitsColumnsAndRows() {
        val (cols, rows) = gridDimensions(800f, 1500f, 8f, 15f)
        assertEquals(100, cols)
        assertEquals(100, rows)
    }

    @Test fun clampsToBounds() {
        val (cols, rows) = gridDimensions(50f, 30f, 8f, 15f)
        assertEquals(20, cols)
        assertEquals(5, rows)
    }

    @Test fun guardsZeroCell() {
        assertEquals(20 to 5, gridDimensions(800f, 1500f, 0f, 0f))
    }

    @Test fun zoomClampsToRange() {
        assertEquals(22f, zoomedFontSizeSp(13f, 5f), 0.001f)
        assertEquals(7f, zoomedFontSizeSp(13f, 0.1f), 0.001f)
        assertEquals(13f, zoomedFontSizeSp(13f, 1f), 0.001f)
    }
}
