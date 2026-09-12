package com.sodre90.cmuxremote.ui.terminal

import androidx.exifinterface.media.ExifInterface
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageSizingTest {

    @Test
    fun aCameraPhotoIsSampledDownToJustAboveTheTargetThenScaledOntoIt() {
        // 4000x3000: /2 = 2000 >= 1568 so sample 2; /4 = 1000 < 1568 so no further.
        assertEquals(2, ImageSizing.sampleSize(4000, 3000))
        assertEquals(1568 to 1176, ImageSizing.targetSize(4000, 3000))
    }

    @Test
    fun aScreenshotThatAlreadyFitsIsLeftAlone() {
        assertEquals(1, ImageSizing.sampleSize(1200, 800))
        assertEquals(1200 to 800, ImageSizing.targetSize(1200, 800))
        assertEquals(1568 to 900, ImageSizing.targetSize(1568, 900))
    }

    @Test
    fun aPortraitKeepsItsOrientationInTheTarget() {
        assertEquals(1176 to 1568, ImageSizing.targetSize(3000, 4000))
    }

    @Test
    fun aHugePanoramaSamplesByPowersOfTwo() {
        // 16000 wide: /2=8000, /4=4000, /8=2000 all >= 1568; /16=1000 is not.
        assertEquals(8, ImageSizing.sampleSize(16000, 2000))
        assertEquals(1568 to 196, ImageSizing.targetSize(16000, 2000))
    }

    @Test
    fun exifOrientationsMapToTheirRotation() {
        assertEquals(0, ImageSizing.rotationDegrees(ExifInterface.ORIENTATION_NORMAL))
        assertEquals(0, ImageSizing.rotationDegrees(ExifInterface.ORIENTATION_UNDEFINED))
        assertEquals(90, ImageSizing.rotationDegrees(ExifInterface.ORIENTATION_ROTATE_90))
        assertEquals(180, ImageSizing.rotationDegrees(ExifInterface.ORIENTATION_ROTATE_180))
        assertEquals(270, ImageSizing.rotationDegrees(ExifInterface.ORIENTATION_ROTATE_270))
    }
}
