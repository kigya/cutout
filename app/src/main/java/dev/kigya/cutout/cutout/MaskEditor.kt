package dev.kigya.cutout.cutout

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.util.Base64
import dev.kigya.cutout.data.ClipboardImages
import dev.kigya.cutout.domain.canCopyEdited
import dev.kigya.cutout.domain.opaqueCount
import java.io.File
import java.io.FileOutputStream
import javax.inject.Inject
import javax.inject.Singleton

sealed interface MaskWrite {
    data class Ready(val path: String) : MaskWrite
    data class Empty(val path: String) : MaskWrite
    data class Failed(val detail: String) : MaskWrite
}

@Singleton
class MaskEditor @Inject constructor(
    private val clipboard: ClipboardImages,
) {
    fun readAlpha(path: String): String? {
        val bitmap = BitmapFactory.decodeFile(path) ?: return null
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val alpha = ByteArray(pixels.size)
        for (index in pixels.indices) {
            alpha[index] = ((pixels[index] ushr 24) and 0xFF).toByte()
        }
        bitmap.recycle()
        return Base64.encodeToString(alpha, Base64.NO_WRAP)
    }

    fun writeMasked(originalPath: String, alphaBase64: String, width: Int, height: Int): MaskWrite {
        if (width <= 0 || height <= 0 || width.toLong() * height > 2048L * 2048L) return MaskWrite.Failed("size")
        val decoded = BitmapFactory.decodeFile(originalPath) ?: return MaskWrite.Failed("original")
        val fitted = if (decoded.width == width && decoded.height == height) {
            decoded
        } else {
            Bitmap.createScaledBitmap(decoded, width, height, true)
        }
        if (fitted !== decoded) decoded.recycle()
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        Canvas(source).drawBitmap(fitted, 0f, 0f, null)
        fitted.recycle()
        source.setHasAlpha(true)
        val alpha = try {
            Base64.decode(alphaBase64, Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            source.recycle()
            return MaskWrite.Failed("alpha")
        }
        if (alpha.size != width * height) {
            source.recycle()
            return MaskWrite.Failed("alpha")
        }
        val pixels = IntArray(width * height)
        source.getPixels(pixels, 0, width, 0, 0, width, height)
        val samples = IntArray(width * height)
        for (index in pixels.indices) {
            val sample = minOf(alpha[index].toInt() and 0xFF, (pixels[index] ushr 24) and 0xFF)
            samples[index] = sample
            pixels[index] = (pixels[index] and 0x00FFFFFF) or (sample shl 24)
        }
        source.setHasAlpha(true)
        source.setPixels(pixels, 0, width, 0, 0, width, height)
        val file = File(sourceDirectory(originalPath), "preview-${java.util.UUID.randomUUID()}.png")
        FileOutputStream(file).use { stream ->
            check(source.compress(Bitmap.CompressFormat.PNG, 100, stream)) { "PNG compression failed" }
        }
        source.recycle()
        return if (canCopyEdited(opaqueCount(samples))) {
            MaskWrite.Ready(file.absolutePath)
        } else {
            MaskWrite.Empty(file.absolutePath)
        }
    }

    fun publish(path: String): String {
        val bitmap = BitmapFactory.decodeFile(path) ?: return "failed"
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val alpha = IntArray(pixels.size) { index -> (pixels[index] ushr 24) and 0xFF }
        val copy = canCopyEdited(opaqueCount(alpha))
        if (!copy) {
            bitmap.recycle()
            return "empty"
        }
        val stream = java.io.ByteArrayOutputStream()
        val compressed = bitmap.compress(Bitmap.CompressFormat.PNG, 100, stream)
        bitmap.recycle()
        if (!compressed || stream.size() == 0) return "failed"
        clipboard.publish(stream.toByteArray(), "png")
        return "copied"
    }

    private fun sourceDirectory(originalPath: String): File {
        val parent = File(originalPath).parentFile
        return (parent ?: File(originalPath).absoluteFile.parentFile).apply { mkdirs() }
    }
}
