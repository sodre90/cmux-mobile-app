package com.sodre90.cmuxremote.ui

import com.sodre90.cmuxremote.R
import com.sodre90.cmuxremote.model.YoloMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class YoloBadgeTest {

    @Test
    fun labelsKnownModes() {
        assertEquals(R.string.yolo_label_always, yoloModeLabel(YoloMode.ALWAYS))
        assertEquals(R.string.yolo_label_all_tools, yoloModeLabel(YoloMode.ALL_TOOLS))
        assertEquals(R.string.yolo_label_bypass, yoloModeLabel(YoloMode.BYPASS))
    }

    @Test
    fun offAndUnknownModesHaveNoBadge() {
        assertNull(yoloModeLabel(YoloMode.OFF))
        assertNull(yoloModeLabel("bogus"))
    }
}
