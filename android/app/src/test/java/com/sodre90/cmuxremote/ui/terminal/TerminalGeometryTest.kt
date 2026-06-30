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

    @Test fun fitSizesFontToFullWidth() {
        // 80 cols at 0.6 px/sp advance: 800 / (80 * 0.6) = 16.67sp, within [7,22].
        assertEquals(16.667f, fitFontSizeSp(800f, 80, 0.6f), 0.01f)
    }

    @Test fun fitHitsLegibilityFloorOnWideOutput() {
        // 400 cols would need ~3.3sp to fit; floor stops at 7 (overflow → pan).
        assertEquals(7f, fitFontSizeSp(800f, 400, 0.6f), 0.001f)
    }

    @Test fun fitCapsOnNarrowOutput() {
        // 20 cols could grow to ~66sp; cap holds it at 22.
        assertEquals(22f, fitFontSizeSp(800f, 20, 0.6f), 0.001f)
    }

    @Test fun fitGuardsDegenerateInput() {
        assertEquals(22f, fitFontSizeSp(800f, 0, 0.6f), 0.001f)
        assertEquals(22f, fitFontSizeSp(0f, 80, 0.6f), 0.001f)
        assertEquals(22f, fitFontSizeSp(800f, 80, 0f), 0.001f)
    }
}
