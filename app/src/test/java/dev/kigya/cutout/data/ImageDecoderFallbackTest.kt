package dev.kigya.cutout.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    application = Application::class,
    shadows = [ImageDecoderFallbackTest.UnavailableBitmapFactory::class],
)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageDecoderFallbackTest {
    @Test fun `ImageDecoder recovers a small transparent PNG when BitmapFactory has no decoder`() {
        UnavailableBitmapFactory.decodeCalls = 0
        val source = png(width = 4, height = 3)

        val decoded = decodeRaster(source)

        assertEquals("BitmapFactory bounds decode must have been shadowed", 1, UnavailableBitmapFactory.decodeCalls)
        assertNotNull(decoded)
        decoded!!
        assertEquals(4, decoded.width)
        assertEquals(3, decoded.height)
        assertEquals(Bitmap.Config.ARGB_8888, decoded.config)
        assertEquals(255, Color.alpha(decoded.getPixel(0, 0)))
        assertEquals(0, Color.alpha(decoded.getPixel(3, 2)))
    }

    @Test fun `ImageDecoder clamps very wide and tall fallback inputs to the maximum side`() {
        UnavailableBitmapFactory.decodeCalls = 0
        val wide = decodeRaster(png(width = 4097, height = 2))
        assertEquals(1, UnavailableBitmapFactory.decodeCalls)
        val tall = decodeRaster(png(width = 2, height = 4097))

        assertEquals(2, UnavailableBitmapFactory.decodeCalls)
        assertNotNull(wide)
        assertNotNull(tall)
        assertEquals(2048, wide!!.width)
        assertEquals(1, wide.height)
        assertEquals(1, tall!!.width)
        assertEquals(2048, tall.height)
        assertNotEquals(Bitmap.Config.HARDWARE, wide.config)
        assertNotEquals(Bitmap.Config.HARDWARE, tall.config)
        wide.recycle()
        tall.recycle()
    }

    private fun png(width: Int, height: Int): ByteArray {
        val source = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        source.eraseColor(Color.TRANSPARENT)
        source.setPixel(0, 0, Color.RED)
        val bytes = ByteArrayOutputStream().also { output ->
            assertTrue(source.compress(Bitmap.CompressFormat.PNG, 100, output))
        }.toByteArray()
        source.recycle()
        return bytes
    }

    /** Simulates a platform where BitmapFactory cannot decode this format; ImageDecoder stays real. */
    @Implements(BitmapFactory::class)
    class UnavailableBitmapFactory {
        companion object {
            @JvmField var decodeCalls = 0

            @Implementation @JvmStatic
            fun decodeByteArray(data: ByteArray, offset: Int, length: Int): Bitmap? {
                decodeCalls += 1
                return null
            }

            @Implementation @JvmStatic
            fun decodeByteArray(
                data: ByteArray,
                offset: Int,
                length: Int,
                options: BitmapFactory.Options?,
            ): Bitmap? {
                decodeCalls += 1
                options?.apply {
                    outWidth = -1
                    outHeight = -1
                    outMimeType = null
                }
                return null
            }
        }
    }
}
