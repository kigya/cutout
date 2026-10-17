package dev.kigya.cutout.cutout

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.app.Application
import android.graphics.Bitmap
import io.mockk.mockk
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.ShadowLegacyBitmap
import org.robolectric.util.ReflectionHelpers

/** Android may return null from Bitmap.copy when allocating software storage fails. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class, shadows = [BitmapAllocationFailureTest.NoCopyBitmap::class])
@GraphicsMode(GraphicsMode.Mode.LEGACY)
class BitmapAllocationFailureTest {
    @Test fun `failed software copy cannot be reported as an edited image`() = runBlocking<Unit> {
        val remover = BackgroundRemover(RuntimeEnvironment.getApplication())
        ReflectionHelpers.setField(remover, "environment", mockk<OrtEnvironment>())
        ReflectionHelpers.setField(remover, "session", mockk<OrtSession>())
        ReflectionHelpers.setField(remover, "inputName", "image")
        val source = Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565)
        assertEquals(Removal.Broken("bitmap"), remover.cut(source))
    }
    @Implements(Bitmap::class)
    class NoCopyBitmap : ShadowLegacyBitmap() {
        @Implementation override fun copy(config: Bitmap.Config?, isMutable: Boolean): Bitmap? = null
    }
}
