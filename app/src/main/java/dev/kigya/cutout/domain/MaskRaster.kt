package dev.kigya.cutout.domain

import kotlin.math.floor

data class PixelBounds(
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
)

fun isnetChw(pixels: IntArray): FloatArray {
    val count = pixels.size
    val out = FloatArray(3 * count)
    for (index in pixels.indices) {
        val pixel = pixels[index]
        out[index] = (((pixel shr 16) and 0xFF) - 128f) / 256f
        out[count + index] = (((pixel shr 8) and 0xFF) - 128f) / 256f
        out[(2 * count) + index] = ((pixel and 0xFF) - 128f) / 256f
    }
    return out
}

fun softAlpha(value: Float): Float {
    // Keep the model's 0.5 decision boundary. The former asymmetric ramp
    // made every probability >= 0.5 opaque and amplified background edges.
    val t = ((value - 0.1f) / 0.8f).coerceIn(0f, 1f)
    return t * t * (3f - 2f * t)
}

fun resampleBilinear(
    source: FloatArray,
    sourceWidth: Int,
    sourceHeight: Int,
    destWidth: Int,
    destHeight: Int,
): FloatArray {
    val dest = FloatArray(destWidth * destHeight)
    if (sourceWidth <= 0 || sourceHeight <= 0 || destWidth <= 0 || destHeight <= 0) return dest
    val xScale = sourceWidth.toFloat() / destWidth.toFloat()
    val yScale = sourceHeight.toFloat() / destHeight.toFloat()
    for (y in 0 until destHeight) {
        val srcY = ((y + 0.5f) * yScale) - 0.5f
        val y0 = floor(srcY.toDouble()).toInt()
        val fy = srcY - y0.toFloat()
        val cy0 = y0.coerceIn(0, sourceHeight - 1)
        val cy1 = (y0 + 1).coerceIn(0, sourceHeight - 1)
        for (x in 0 until destWidth) {
            val srcX = ((x + 0.5f) * xScale) - 0.5f
            val x0 = floor(srcX.toDouble()).toInt()
            val fx = srcX - x0.toFloat()
            val cx0 = x0.coerceIn(0, sourceWidth - 1)
            val cx1 = (x0 + 1).coerceIn(0, sourceWidth - 1)
            val top = source[(cy0 * sourceWidth) + cx0] +
                ((source[(cy0 * sourceWidth) + cx1] - source[(cy0 * sourceWidth) + cx0]) * fx)
            val bottom = source[(cy1 * sourceWidth) + cx0] +
                ((source[(cy1 * sourceWidth) + cx1] - source[(cy1 * sourceWidth) + cx0]) * fx)
            dest[(y * destWidth) + x] = top + ((bottom - top) * fy)
        }
    }
    return dest
}

fun boundsOfAlpha(pixels: IntArray, width: Int, height: Int, minAlpha: Int): PixelBounds? {
    var left = width
    var top = height
    var right = -1
    var bottom = -1
    for (y in 0 until height) {
        val row = y * width
        for (x in 0 until width) {
            val alpha = (pixels[row + x] ushr 24) and 0xFF
            if (alpha >= minAlpha) {
                if (x < left) left = x
                if (y < top) top = y
                if (x > right) right = x
                if (y > bottom) bottom = y
            }
        }
    }
    if (right < left || bottom < top) return null
    return PixelBounds(left, top, right - left + 1, bottom - top + 1)
}
