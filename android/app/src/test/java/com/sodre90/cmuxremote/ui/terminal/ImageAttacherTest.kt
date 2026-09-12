package com.sodre90.cmuxremote.ui.terminal

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Real decoding, in Robolectric's native graphics mode: a JPEG is written
 * with an EXIF orientation and read back through the same path a picked
 * photo takes. The file scheme is used so the platform ContentResolver opens
 * it without any shadow registration.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [Build.VERSION_CODES.UPSIDE_DOWN_CAKE], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageAttacherTest {

    private val app: Application get() = ApplicationProvider.getApplicationContext()

    /** A [width]x[height] JPEG, solid [color], tagged with [orientation]. */
    private fun jpeg(width: Int, height: Int, color: Int, orientation: Int): Uri {
        val file = File.createTempFile("attach-", ".jpg", app.cacheDir)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
        ExifInterface(file.absolutePath).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, orientation.toString())
            saveAttributes()
        }
        return Uri.fromFile(file)
    }

    private fun decoded(prepared: PreparedImage): Bitmap =
        BitmapFactory.decodeByteArray(prepared.bytes, 0, prepared.bytes.size)

    @Test
    fun aLandscapePhotoTaggedAsRotatedComesOutUprightAndWithinTheBound() {
        // Stored 3200x1800 but shot in portrait: the tag says rotate 90.
        val uri = jpeg(3200, 1800, Color.RED, ExifInterface.ORIENTATION_ROTATE_90)
        val preview = ImageAttacher(app.contentResolver).preview(uri)

        val copy = preview.downscaled
        assertFalse(copy.original)
        assertEquals(882 to 1568, copy.width to copy.height)
        val pixels = decoded(copy)
        assertEquals(882 to 1568, pixels.width to pixels.height)
        assertTrue(copy.bytes.size < 200_000)
        // The copy is a fresh JPEG: no orientation tag survives, so nothing
        // downstream rotates it a second time.
        val exif = ExifInterface(copy.bytes.inputStream())
        assertEquals(
            ExifInterface.ORIENTATION_UNDEFINED,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
        )
        assertTrue(preview.thumbnail.height <= 320 && preview.thumbnail.width <= 320)
    }

    @Test
    fun aScreenshotThatFitsIsReEncodedAtItsOwnSize() {
        val uri = jpeg(1080, 600, Color.BLUE, ExifInterface.ORIENTATION_NORMAL)
        val copy = ImageAttacher(app.contentResolver).preview(uri).downscaled
        assertEquals(1080 to 600, copy.width to copy.height)
    }

    @Test
    fun theOriginalIsTheFileByteForByte() {
        val uri = jpeg(640, 480, Color.GREEN, ExifInterface.ORIENTATION_ROTATE_180)
        val original = ImageAttacher(app.contentResolver).original(uri)
        assertTrue(original.original)
        assertEquals(640 to 480, original.width to original.height)
        assertTrue(File(uri.path!!).readBytes().contentEquals(original.bytes))
        // Including its EXIF -- that is the point of "original".
        val exif = ExifInterface(original.bytes.inputStream())
        assertEquals(
            ExifInterface.ORIENTATION_ROTATE_180,
            exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED),
        )
    }

    @Test
    fun aFileUriWithNoSizeColumnMeansTheOriginalIsNotOffered() {
        val uri = jpeg(640, 480, Color.GREEN, ExifInterface.ORIENTATION_NORMAL)
        val preview = ImageAttacher(app.contentResolver).preview(uri)
        assertEquals(0L, preview.originalBytes)
        assertFalse(preview.originalFits)
        assertTrue(preview.name.endsWith(".jpg"))
    }
}
