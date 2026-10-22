package dev.kigya.cutout.data

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import dev.kigya.cutout.cutout.MaskEditor
import dev.kigya.cutout.cutout.MaskWrite
import java.io.File
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class MaskInputEdgesTest {
    private fun original(): File {
        val app = RuntimeEnvironment.getApplication()
        return File(app.cacheDir, "mask-edge-${java.util.UUID.randomUUID()}.png").also { file ->
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            bitmap.eraseColor(Color.RED)
            try { file.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) } }
            finally { bitmap.recycle() }
        }
    }
    @Test fun `invalid base64 encoding is rejected without publishing an edited file`() {
        val app = RuntimeEnvironment.getApplication()
        val source = original()
        val editor = MaskEditor(ClipboardImages(app))
        // A one-character group cannot encode any byte; unlike punctuation it cannot be ignored.
        assertEquals(MaskWrite.Failed("alpha"), editor.writeMasked(source.path, "A", 2, 2))
    }
    @Test fun `height-only source resize preserves dimensions and applied alpha`() {
        val app = RuntimeEnvironment.getApplication()
        val source = original()
        val encoded = Base64.encodeToString(byteArrayOf(-1, 64), Base64.NO_WRAP)
        val result = MaskEditor(ClipboardImages(app)).writeMasked(source.path, encoded, 2, 1)
        assertTrue(result is MaskWrite.Ready)
        val bitmap = BitmapFactory.decodeFile((result as MaskWrite.Ready).path)
        try {
            assertEquals(2, bitmap.width)
            assertEquals(1, bitmap.height)
            assertEquals(255, Color.alpha(bitmap.getPixel(0, 0)))
            assertEquals(64, Color.alpha(bitmap.getPixel(1, 0)))
        } finally { bitmap.recycle() }
    }
}
