package com.sodre90.cmuxremote.ui.terminal

import android.content.ContentResolver
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.provider.OpenableColumns
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream

/**
 * The longest side a photo is scaled down to before it is sent. Claude's API
 * resizes anything larger to this itself, so nothing the agent sees is lost,
 * and a 3-8 MB camera photo comes out at a few hundred KB.
 */
internal const val ATTACH_MAX_EDGE = 1568

/** JPEG quality for the downscaled copy. */
internal const val ATTACH_JPEG_QUALITY = 85

/**
 * The largest original the bridge accepts; mirrors `attachmentMaxBytes` in
 * bridge/internal/server/attachments.go. Above it the "send original"
 * choice is not offered and the downscaled copy goes instead.
 */
const val ATTACH_ORIGINAL_CAP_BYTES = 10L shl 20

/**
 * The arithmetic of fitting an image inside [ATTACH_MAX_EDGE], kept apart
 * from the bitmap calls so it can be tested without a device.
 */
internal object ImageSizing {
    /**
     * The power-of-two subsampling that keeps the decoded image at least
     * [maxEdge] on its longest side -- the coarsest BitmapFactory can do
     * without going below the target, which the final scale then meets
     * exactly. Decoding at full size first would put a 12 MP photo (48 MB
     * of ARGB) on the heap for nothing.
     */
    fun sampleSize(width: Int, height: Int, maxEdge: Int = ATTACH_MAX_EDGE): Int {
        var sample = 1
        while (maxOf(width, height) / (sample * 2) >= maxEdge) sample *= 2
        return sample
    }

    /** The dimensions the image ends up at: unchanged when it already fits,
     *  otherwise scaled so its longest side is [maxEdge], aspect kept. */
    fun targetSize(width: Int, height: Int, maxEdge: Int = ATTACH_MAX_EDGE): Pair<Int, Int> {
        val longest = maxOf(width, height)
        if (longest <= maxEdge) return width to height
        val scale = maxEdge.toDouble() / longest
        return maxOf(1, Math.round(width * scale).toInt()) to maxOf(1, Math.round(height * scale).toInt())
    }

    /** The clockwise rotation an EXIF orientation tag asks for; the mirrored
     *  variants are treated as their rotation alone, which is what every
     *  viewer that ignores mirroring shows anyway. */
    fun rotationDegrees(exifOrientation: Int): Int = when (exifOrientation) {
        ExifInterface.ORIENTATION_ROTATE_90, ExifInterface.ORIENTATION_TRANSPOSE -> 90
        ExifInterface.ORIENTATION_ROTATE_180, ExifInterface.ORIENTATION_FLIP_VERTICAL -> 180
        ExifInterface.ORIENTATION_ROTATE_270, ExifInterface.ORIENTATION_TRANSVERSE -> 270
        else -> 0
    }
}

/** An image ready to go on the wire. */
class PreparedImage(
    val bytes: ByteArray,
    val width: Int,
    val height: Int,
    /** True when [bytes] are the file exactly as picked, EXIF and all. */
    val original: Boolean,
)

/** What the confirmation shows before anything is sent. */
class AttachmentPreview(
    val thumbnail: Bitmap,
    val downscaled: PreparedImage,
    val originalBytes: Long,
    val name: String,
) {
    val originalFits: Boolean get() = originalBytes in 1..ATTACH_ORIGINAL_CAP_BYTES
}

/**
 * Turns a picked image URI into bytes: a downscaled, upright JPEG by default,
 * or the file as-is when the user asks for the original and it fits. Every
 * method does I/O and decoding and belongs off the main thread.
 */
class ImageAttacher(private val resolver: ContentResolver) {

    /** Decodes the photo once, at the size that will be sent; the thumbnail
     *  is cut from that rather than decoded again from the file. */
    fun preview(uri: Uri): AttachmentPreview {
        val upright = decodeBounded(uri, maxEdge = ATTACH_MAX_EDGE)
        val out = ByteArrayOutputStream()
        upright.compress(Bitmap.CompressFormat.JPEG, ATTACH_JPEG_QUALITY, out)
        val (tw, th) = ImageSizing.targetSize(upright.width, upright.height, THUMBNAIL_EDGE)
        return AttachmentPreview(
            thumbnail = Bitmap.createScaledBitmap(upright, tw, th, true),
            downscaled = PreparedImage(out.toByteArray(), upright.width, upright.height, original = false),
            originalBytes = originalSize(uri),
            name = displayName(uri),
        )
    }

    fun original(uri: Uri): PreparedImage {
        val bytes = open(uri).use { it.readBytes() }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        return PreparedImage(bytes, bounds.outWidth, bounds.outHeight, original = true)
    }

    /** Decodes, rotates upright per EXIF, and scales so the longest side is
     *  at most [maxEdge]. Rotation comes before the final scale so a portrait
     *  photo's target is computed on its upright dimensions. */
    private fun decodeBounded(uri: Uri, maxEdge: Int): Bitmap {
        // Bounds only: decodeStream returns null on purpose in this mode, so
        // the stream and the result are checked separately.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        open(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) throw IOException("not an image: $uri")
        val options = BitmapFactory.Options().apply {
            inSampleSize = ImageSizing.sampleSize(bounds.outWidth, bounds.outHeight, maxEdge)
        }
        val sampled = open(uri).use { BitmapFactory.decodeStream(it, null, options) }
            ?: throw IOException("cannot decode $uri")
        val upright = rotated(sampled, exifRotation(uri))
        val (w, h) = ImageSizing.targetSize(upright.width, upright.height, maxEdge)
        if (w == upright.width && h == upright.height) return upright
        return Bitmap.createScaledBitmap(upright, w, h, true)
    }

    private fun open(uri: Uri): InputStream = resolver.openInputStream(uri) ?: throw IOException("cannot open $uri")

    private fun exifRotation(uri: Uri): Int = open(uri).use { stream ->
        runCatching {
            ExifInterface(stream).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
    }.let(ImageSizing::rotationDegrees)

    private fun rotated(bitmap: Bitmap, degrees: Int): Bitmap {
        if (degrees == 0) return bitmap
        val matrix = Matrix().apply { postRotate(degrees.toFloat()) }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    }

    private fun originalSize(uri: Uri): Long =
        resolver.query(uri, arrayOf(OpenableColumns.SIZE), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getLong(column) else 0L
        } ?: 0L

    private fun displayName(uri: Uri): String =
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            val column = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            if (column >= 0 && cursor.moveToFirst() && !cursor.isNull(column)) cursor.getString(column) else null
        } ?: uri.lastPathSegment.orEmpty()

    private companion object {
        const val THUMBNAIL_EDGE = 320
    }
}

/** The confirmation dialog's state while an image is being staged. */
sealed interface AttachmentDraft {
    data object Preparing : AttachmentDraft
    data object Unreadable : AttachmentDraft
    data class Ready(val preview: AttachmentPreview) : AttachmentDraft
}
