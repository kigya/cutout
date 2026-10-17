package dev.kigya.cutout.cutout

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OnnxValue
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.app.Application
import android.content.ContextWrapper
import android.content.res.AssetManager
import java.io.ByteArrayInputStream
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import io.mockk.mockk
import io.mockk.verify
import java.nio.FloatBuffer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class BackgroundRemoverTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun image() = Bitmap.createBitmap(intArrayOf(Color.RED, Color.BLUE, Color.GREEN, Color.WHITE), 2, 2, Bitmap.Config.ARGB_8888)
    private fun prepared(engine: OrtSession) = BackgroundRemover(app).apply {
        ReflectionHelpers.setField(this, "environment", OrtEnvironment.getEnvironment())
        ReflectionHelpers.setField(this, "session", engine)
        ReflectionHelpers.setField(this, "inputName", "image")
    }
    private fun output(values: FloatArray, width: Long = 2, height: Long = 2): OnnxTensor {
        val info = mockk<TensorInfo>()
        every { info.shape } returns longArrayOf(1, 1, height, width)
        return mockk {
            every { this@mockk.info } returns info
            every { floatBuffer } returns FloatBuffer.wrap(values)
        }
    }
    private fun result(value: OnnxValue): OrtSession.Result = mockk(relaxed = true) {
        every { get(0) } returns value
    }
    @Test fun `unprepared model cannot segment or return an editable success`() = runBlocking<Unit> {
        val remover = BackgroundRemover(app)
        assertEquals(Removal.Broken("model"), remover.cut(image()))
        ReflectionHelpers.setField(remover, "session", mockk<OrtSession>())
        assertEquals(Removal.Broken("model"), remover.cut(image()))
        ReflectionHelpers.setField(remover, "inputName", "image")
        assertEquals(Removal.Broken("model"), remover.cut(image()))
    }
    @Test fun `real bundled fixture runs through cached model and preserves transparent output`() = runBlocking<Unit> {
        val remover = BackgroundRemover(app)
        remover.prepare().getOrThrow()
        val engine = ReflectionHelpers.getField<OrtSession>(remover, "session")
        try {
            assertTrue(remover.prepare().isSuccess)
            assertSame(engine, ReflectionHelpers.getField<OrtSession>(remover, "session"))
            val source = app.assets.open("fixture.png").use { BitmapFactory.decodeStream(it) }!!
            assertTrue("fixture must remain small", source.width <= 128 && source.height <= 128)
            val cut = remover.cut(source)
            assertTrue(cut.toString(), cut is Removal.Ready)
            cut as Removal.Ready
            val pixels = IntArray(cut.frame.width * cut.frame.height)
            cut.frame.getPixels(pixels, 0, cut.frame.width, 0, 0, cut.frame.width, cut.frame.height)
            assertTrue(pixels.any { Color.alpha(it) == 0 })
            assertTrue(pixels.any { Color.alpha(it) > 0 })
            assertFalse(source.isRecycled)
            if (cut.bitmap !== cut.frame) cut.bitmap.recycle()
            cut.frame.recycle(); source.recycle()
        } finally { engine.close() }
    }
    @Test fun `model run failure closes the input tensor and leaves the caller bitmap valid`() = runBlocking<Unit> {
        val engine = mockk<OrtSession>()
        var input: OnnxTensor? = null
        every { engine.run(any<Map<String, OnnxTensor>>()) } answers {
            input = firstArg<Map<String, OnnxTensor>>().getValue("image")
            throw IllegalStateException("inference failed")
        }
        val source = image()
        assertEquals(Removal.Broken("inference failed"), prepared(engine).cut(source))
        assertTrue(input!!.isClosed)
        assertFalse(source.isRecycled)
    }
    @Test fun `mask rejection always closes result and input`() = runBlocking<Unit> {
        for (value in listOf<OnnxValue>(mockk(), output(floatArrayOf(0f,0f,0f,0f)), output(floatArrayOf(0f), 2, 2), output(floatArrayOf(), 0, 2), output(floatArrayOf(), 2, 0))) {
            val result = result(value)
            val engine = mockk<OrtSession>()
            var input: OnnxTensor? = null
            every { engine.run(any<Map<String, OnnxTensor>>()) } answers {
                input = firstArg<Map<String, OnnxTensor>>().getValue("image"); result
            }
            val cut = prepared(engine).cut(image())
            assertTrue(cut is Removal.SubjectNotFound || cut == Removal.Broken("mask"))
            assertTrue(input!!.isClosed)
            verify(exactly = 1) { result.close() }
        }
    }
    @Test fun `usable masks retain source alpha and crop only empty outside bounds`() = runBlocking<Unit> {
        for ((mask, width, height) in listOf(Triple(floatArrayOf(0f,1f,0f,1f), 1, 2), Triple(floatArrayOf(1f,0f,0f,1f), 2, 2), Triple(floatArrayOf(0f,0f,1f,1f), 2, 1), Triple(floatArrayOf(1f,0f,1f,0f), 1, 2), Triple(floatArrayOf(1f,1f,0f,0f), 2, 1))) {
            val engine = mockk<OrtSession>()
            val result = result(output(mask))
            every { engine.run(any<Map<String, OnnxTensor>>()) } returns result
            val source = image()
            val cut = prepared(engine).cut(source) as Removal.Ready
            assertEquals(width, cut.bitmap.width)
            assertEquals(height, cut.bitmap.height)
            assertEquals(2, cut.frame.width)
            assertFalse(source.isRecycled)
            verify(exactly = 1) { result.close() }
        }
        val engine = mockk<OrtSession>()
        every { engine.run(any<Map<String, OnnxTensor>>()) } returns result(output(floatArrayOf(0f,1f,0f,1f)))
        val transparent = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
        assertEquals(Removal.SubjectNotFound, prepared(engine).cut(transparent))
    }
    @Test fun `failed model load remains retryable without publishing a partial session`() = runBlocking<Unit> {
        val assets = mockk<AssetManager>()
        every { assets.open("isnet.onnx") } answers { ByteArrayInputStream(byteArrayOf(0, 1, 2)) }
        val context = object : ContextWrapper(app) { override fun getAssets() = assets }
        val remover = BackgroundRemover(context)
        assertTrue(remover.prepare().isFailure)
        assertTrue(remover.prepare().isFailure)
        assertEquals(Removal.Broken("model"), remover.cut(image()))
        verify(exactly = 2) { assets.open("isnet.onnx") }
    }
    @Test fun `invalid session without input is closed and not cached`() = runBlocking<Unit> {
        val assets = mockk<AssetManager>()
        every { assets.open("isnet.onnx") } answers { ByteArrayInputStream(byteArrayOf(0)) }
        val context = object : ContextWrapper(app) { override fun getAssets() = assets }
        val environment = mockk<OrtEnvironment>()
        val session = mockk<OrtSession>(relaxed = true)
        every { environment.createSession(any<ByteArray>(), any<OrtSession.SessionOptions>()) } returns session
        every { session.inputNames } returns emptySet()
        mockkStatic(OrtEnvironment::class)
        try {
            every { OrtEnvironment.getEnvironment() } returns environment
            val remover = BackgroundRemover(context)
            assertTrue(remover.prepare().isFailure)
            assertEquals(Removal.Broken("model"), remover.cut(image()))
            verify(exactly = 1) { session.close() }
        } finally { unmockkStatic(OrtEnvironment::class) }
    }
    @Test fun `malformed output metadata cannot produce success`() = runBlocking<Unit> {
        val engine = mockk<OrtSession>()
        val output = mockk<OnnxTensor>()
        every { output.info } returns null
        val result = result(output)
        every { engine.run(any<Map<String, OnnxTensor>>()) } returns result
        assertEquals(Removal.Broken("mask"), prepared(engine).cut(image()))
        verify(exactly = 1) { result.close() }
    }
    @Test fun `nonARGB frame conversion and exact model size keep the original caller owned`() = runBlocking<Unit> {
        for (source in listOf(Bitmap.createBitmap(2, 2, Bitmap.Config.RGB_565), Bitmap.createBitmap(1024, 1024, Bitmap.Config.ARGB_8888), Bitmap.createBitmap(1024, 2, Bitmap.Config.ARGB_8888))) {
            source.eraseColor(Color.RED)
            val engine = mockk<OrtSession>()
            every { engine.run(any<Map<String, OnnxTensor>>()) } returns result(output(floatArrayOf(1f,0f,0f,1f)))
            assertTrue(prepared(engine).cut(source) is Removal.Ready)
            assertFalse(source.isRecycled)
            source.recycle()
        }
        val engine = mockk<OrtSession>()
        every { engine.run(any<Map<String, OnnxTensor>>()) } throws IllegalStateException()
        assertEquals(Removal.Broken("IllegalStateException"), prepared(engine).cut(image()))
    }

}
