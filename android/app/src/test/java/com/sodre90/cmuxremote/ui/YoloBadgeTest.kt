package com.sodre90.cmuxremote.ui

import com.sodre90.cmuxremote.model.YoloMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YoloBadgeTest {

    @Test
    fun labelsKnownModes() {
        assertEquals("ALWAYS", yoloModeLabel(YoloMode.ALWAYS))
        assertEquals("ALL TOOLS", yoloModeLabel(YoloMode.ALL_TOOLS))
        assertEquals("BYPASS", yoloModeLabel(YoloMode.BYPASS))
    }

    @Test
    fun offAndUnknownModesHaveNoBadge() {
        assertNull(yoloModeLabel(YoloMode.OFF))
        assertNull(yoloModeLabel("bogus"))
    }
}
