package dev.kigya.cutout.data

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.os.Build
import androidx.exifinterface.media.ExifInterface
import java.io.ByteArrayInputStream
import java.nio.ByteBuffer
import kotlin.math.max

private const val MAX_SIDE = 2048

fun decodeRaster(bytes: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
        return decodeWithImageDecoder(bytes)
    }
    val sample = sampleSize(bounds.outWidth, bounds.outHeight)
    val options = BitmapFactory.Options().apply {
        inSampleSize = sample
        inPreferredConfig = Bitmap.Config.ARGB_8888
    }
    val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        ?: return decodeWithImageDecoder(bytes)
    return rotate(decoded, bytes)
}

private fun decodeWithImageDecoder(bytes: ByteArray): Bitmap? {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return null
    return try {
        val source = ImageDecoder.createSource(ByteBuffer.wrap(bytes))
        ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
            val side = max(info.size.width, info.size.height)
            if (side > MAX_SIDE) {
                val scale = MAX_SIDE.toFloat() / side.toFloat()
                decoder.setTargetSize(
                    (info.size.width * scale).toInt().coerceAtLeast(1),
                    (info.size.height * scale).toInt().coerceAtLeast(1),
                )
            }
            decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
        }
    } catch (_: Exception) {
        null
    }
}

private fun sampleSize(width: Int, height: Int): Int {
    var sample = 1
    while (width / sample > MAX_SIDE || height / sample > MAX_SIDE) {
        sample *= 2
    }
    return sample
}

private fun rotate(bitmap: Bitmap, bytes: ByteArray): Bitmap {
    val orientation = try {
        ExifInterface(ByteArrayInputStream(bytes)).getAttributeInt(
            ExifInterface.TAG_ORIENTATION,
            ExifInterface.ORIENTATION_NORMAL,
        )
    } catch (_: Exception) {
        ExifInterface.ORIENTATION_NORMAL
    }
    val degrees = when (orientation) {
        ExifInterface.ORIENTATION_ROTATE_90 -> 90f
        ExifInterface.ORIENTATION_ROTATE_180 -> 180f
        ExifInterface.ORIENTATION_ROTATE_270 -> 270f
        else -> return bitmap
    }
    val matrix = Matrix().apply { postRotate(degrees) }
    val rotated = Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
    if (rotated != bitmap) bitmap.recycle()
    return rotated
}
