package dev.kigya.cutout.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MaskRasterTest {
    @Test
    fun stretchAndFractionKeepAPartialSubject() {
        val stretched = stretchUnit(floatArrayOf(0f, 0f, 1f, 0.2f))
        assertEquals(0.25f, MaskPolicy.foregroundFraction(stretched), 0.0001f)
        assertEquals(true, MaskPolicy.evaluate(MaskPolicy.foregroundFraction(stretched)).usable)
    }

    @Test
    fun bilinearKeepsAFlatMaskFlat() {
        val resampled = resampleBilinear(floatArrayOf(0.4f), 1, 1, 2, 2)
        assertEquals(4, resampled.size)
        resampled.forEach { sample -> assertEquals(0.4f, sample, 0.0001f) }
    }

    @Test
    fun cropFollowsOpaquePixels() {
        val pixels = IntArray(16)
        pixels[5] = 0xFF000000.toInt()
        pixels[10] = 0x80000000.toInt()
        val bounds = boundsOfAlpha(pixels, 4, 4, minAlpha = 8)
        assertEquals(PixelBounds(left = 1, top = 1, width = 2, height = 2), bounds)
        assertNull(boundsOfAlpha(IntArray(4), 2, 2, minAlpha = 8))
    }

    @Test
    fun whitePixelUsesIsnetScale() {
        val chw = isnetChw(intArrayOf(0xFFFFFFFF.toInt()))
        assertEquals((255f - 128f) / 256f, chw[0], 0.001f)
        assertEquals(chw[0], chw[1], 0.001f)
        assertEquals(chw[0], chw[2], 0.001f)
    }

    @Test
    fun softAlphaDropsAShadowGhostAndKeepsASolidSubject() {
        assertEquals(0f, softAlpha(0.12f), 0.001f)
        assertEquals(1f, softAlpha(0.99f), 0.001f)
        val edge = softAlpha(0.34f)
        assertTrue(edge > 0.1f && edge < 0.9f)
    }

    @Test
    fun constantAndEmptyMasksHaveDeterministicFallbacks() {
        assertEquals(0f, stretchUnit(floatArrayOf(0.4f, 0.4f, 0.4f)).sum(), 0f)
        assertTrue(stretchUnit(floatArrayOf()).isEmpty())

        assertEquals(listOf(0f, 0f, 0f, 0f), resampleBilinear(floatArrayOf(1f), 0, 1, 2, 2).toList())
        assertEquals(listOf(0f, 0f, 0f, 0f), resampleBilinear(floatArrayOf(1f), 1, 0, 2, 2).toList())
        assertTrue(resampleBilinear(floatArrayOf(1f), 1, 1, 0, 2).isEmpty())
        assertTrue(resampleBilinear(floatArrayOf(1f), 1, 1, 2, 0).isEmpty())
    }

    @Test
    fun alphaBoundsUseInclusiveThresholdAndReturnNullWhenNothingQualifies() {
        val pixels = intArrayOf(0x07ffffff, 0x08ffffff, 0x07ffffff, 0x00ffffff)
        assertEquals(PixelBounds(left = 1, top = 0, width = 1, height = 1), boundsOfAlpha(pixels, 2, 2, 8))
        assertNull(boundsOfAlpha(pixels, 2, 2, 9))
        assertEquals(
            PixelBounds(left = 0, top = 0, width = 2, height = 2),
            boundsOfAlpha(intArrayOf(0x08ffffff, 0x08ffffff, 0x08ffffff, 0x08ffffff), 2, 2, 8),
        )
    }
}
