package dev.kigya.cutout.cutout

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Color
import dev.kigya.cutout.data.ClipboardImages
import dev.kigya.cutout.data.EditSessions
import dev.kigya.cutout.data.IncomingImage
import dev.kigya.cutout.domain.Messages
import java.io.ByteArrayOutputStream
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ImageCutterTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    private fun sourcePng(): ByteArray {
        val bitmap = Bitmap.createBitmap(intArrayOf(Color.RED, Color.BLUE, Color.GREEN, Color.WHITE), 2, 2, Bitmap.Config.ARGB_8888)
        return ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray()
    }

    private fun cutter(remover: BackgroundRemover, clipboard: ClipboardImages = mockk(relaxed = true)) =
        ImageCutter(app, remover, clipboard, EditSessions(app))

    @Test
    fun `invalid and undecodable images fail before model or publication`() = runTest {
        val remover = mockk<BackgroundRemover>()
        val clipboard = mockk<ClipboardImages>(relaxed = true)
        val subject = cutter(remover, clipboard)
        val invalid = subject.cut(IncomingImage.Invalid("invalid"))
        val undecodable = subject.cut(IncomingImage.Raster(byteArrayOf(1, 2, 3)))
        assertEquals(CutOutcome.Failed("invalid"), invalid)
        assertEquals(CutOutcome.Failed(Messages.READ_FAILED), undecodable)
        coVerify(exactly = 0) { remover.prepare() }
        verify(exactly = 0) { clipboard.publish(any(), any()) }
    }

    @Test
    fun `solid backdrop svg publishes the stripped vector and completes progress`() = runTest {
        val remover = mockk<BackgroundRemover>()
        val clipboard = mockk<ClipboardImages>()
        val content = mutableListOf<ByteArray>()
        every { clipboard.publish(capture(content), "svg") } returns android.net.Uri.parse("content://cutout/1")
        val listener = RecordingListener()
        val outcome = cutter(remover, clipboard).cut(
            IncomingImage.Svg("""<svg viewBox="0 0 10 10"><rect width="100%" height="100%" fill="white"/><circle cx="5" cy="5" r="2"/></svg>"""),
            listener,
        )
        assertEquals(CutOutcome.CopiedSvg(Messages.COPIED), outcome)
        assertEquals(listOf(8, 100), listener.progress)
        assertTrue(content.single().toString(Charsets.UTF_8).contains("<circle"))
        coVerify(exactly = 0) { remover.prepare() }
        verify(exactly = 1) { clipboard.publish(any(), "svg") }
    }

    @Test
    fun `malformed svg is rejected and cannot reach clipboard`() = runTest {
        val clipboard = mockk<ClipboardImages>(relaxed = true)
        val remover = mockk<BackgroundRemover>()
        val outcome = cutter(remover, clipboard).cut(IncomingImage.Svg("<svg><"))
        assertEquals(CutOutcome.Failed(Messages.READ_FAILED), outcome)
        verify(exactly = 0) { clipboard.publish(any(), any()) }
        coVerify(exactly = 0) { remover.prepare() }
    }

    @Test
    fun `raster success writes preview and edit pair while reporting ordered progress`() = runTest {
        val remover = mockk<BackgroundRemover>()
        coEvery { remover.prepare() } returns Result.success(Unit)
        coEvery { remover.cut(any()) } coAnswers {
            val input = firstArg<Bitmap>()
            val frame = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
            val tight = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            Removal.Ready(tight, frame)
        }
        val listener = RecordingListener()
        val outcome = cutter(remover).cut(IncomingImage.Raster(sourcePng()), listener)
        assertTrue(outcome is CutOutcome.CopiedRaster)
        outcome as CutOutcome.CopiedRaster
        assertEquals(listOf(8, 18, 28, 92, 100), listener.progress)
        assertTrue(File(outcome.previewPath).isFile)
        assertTrue(File(outcome.edit.originalPath).isFile)
        assertTrue(File(outcome.edit.cutoutPath).isFile)
        assertTrue(File(listener.previews.single()).isFile)
        assertFalse(outcome.previewPath == listener.previews.single())
        assertEquals(2, outcome.edit.width)
        assertEquals(2, outcome.edit.height)
        coVerify(exactly = 1) { remover.prepare() }
        coVerify(exactly = 1) { remover.cut(any()) }
    }

    @Test
    fun `full-frame remover result shares one bitmap and stores a valid edit`() = runTest {
        val remover = mockk<BackgroundRemover>()
        coEvery { remover.prepare() } returns Result.success(Unit)
        coEvery { remover.cut(any()) } coAnswers {
            val full = firstArg<Bitmap>()
            Removal.Ready(bitmap = full, frame = full)
        }

        val outcome = cutter(remover).cut(IncomingImage.Raster(sourcePng()))

        assertTrue(outcome is CutOutcome.CopiedRaster)
        outcome as CutOutcome.CopiedRaster
        assertTrue(File(outcome.edit.originalPath).isFile)
        assertTrue(File(outcome.edit.cutoutPath).isFile)
        assertEquals(2, outcome.edit.width)
        assertEquals(2, outcome.edit.height)
    }

    @Test
    fun `preview observer failure unwinds image ownership and releases the cutter`() = runTest {
        val remover = mockk<BackgroundRemover>()
        val subject = cutter(remover)
        val observer = object : CutListener {
            override fun onPreview(path: String) { throw IllegalStateException("preview listener") }
        }

        try {
            subject.cut(IncomingImage.Raster(sourcePng()), observer)
            throw AssertionError("observer failure should propagate to the bridge")
        } catch (expected: IllegalStateException) {
            assertEquals("preview listener", expected.message)
        }

        assertEquals(CutOutcome.Failed("still available"), subject.cut(IncomingImage.Invalid("still available")))
        coVerify(exactly = 0) { remover.prepare() }
    }

    @Test
    fun `raster cut remains successful when observer relies on default no-op callbacks`() = runTest {
        val remover = mockk<BackgroundRemover>()
        coEvery { remover.prepare() } returns Result.success(Unit)
        coEvery { remover.cut(any()) } returns Removal.SubjectNotFound

        val outcome = cutter(remover).cut(IncomingImage.Raster(sourcePng()), object : CutListener {})

        assertTrue(outcome is CutOutcome.Manual)
        assertEquals(Messages.MANUAL, (outcome as CutOutcome.Manual).message)
        coVerify(exactly = 1) { remover.cut(any()) }
    }

    @Test
    fun `model failure stops before removal and manual subject result stores editable original`() = runTest {
        val remover = mockk<BackgroundRemover>()
        coEvery { remover.prepare() } returns Result.failure(IllegalStateException("model"))
        val listener = RecordingListener()
        val failed = cutter(remover).cut(IncomingImage.Raster(sourcePng()), listener)
        assertEquals(CutOutcome.Failed(Messages.MODEL_FAILED), failed)
        coVerify(exactly = 0) { remover.cut(any()) }
        coEvery { remover.prepare() } returns Result.success(Unit)
        coEvery { remover.cut(any()) } returns Removal.SubjectNotFound
        val manual = cutter(remover).cut(IncomingImage.Raster(sourcePng()), listener)
        assertTrue(manual is CutOutcome.Manual)
        manual as CutOutcome.Manual
        assertEquals(Messages.MANUAL, manual.message)
        assertTrue(File(manual.edit.originalPath).isFile)
        assertTrue(File(manual.edit.cutoutPath).isFile)
        assertEquals(listOf(8, 18, 8, 18, 28, 92, 100), listener.progress)
    }

    @Test
    fun `rasterized svg delegates to remover and reports svg png outcome`() = runTest {
        val remover = mockk<BackgroundRemover>()
        coEvery { remover.prepare() } returns Result.success(Unit)
        coEvery { remover.cut(any()) } coAnswers {
            val input = firstArg<Bitmap>()
            val frame = Bitmap.createBitmap(input.width, input.height, Bitmap.Config.ARGB_8888)
            val tight = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
            Removal.Ready(tight, frame)
        }
        val outcome = cutter(remover).cut(
            IncomingImage.Svg("""<svg xmlns="http://www.w3.org/2000/svg" width="2" height="2"><circle cx="1" cy="1" r="1" fill="red"/></svg>"""),
        )
        assertTrue(outcome is CutOutcome.CopiedRaster)
        assertEquals(Messages.SVG_AS_PNG, (outcome as CutOutcome.CopiedRaster).message)
        coVerify(exactly = 1) { remover.prepare() }
        coVerify(exactly = 1) { remover.cut(any()) }
    }

    @Test
    fun `svg without document dimensions uses bounded fallback canvas`() = runTest {
        val remover = mockk<BackgroundRemover>()
        coEvery { remover.prepare() } returns Result.success(Unit)
        coEvery { remover.cut(any()) } returns Removal.SubjectNotFound
        val outcome = cutter(remover).cut(
            IncomingImage.Svg("""<svg xmlns="http://www.w3.org/2000/svg"><circle cx="2" cy="2" r="1" fill="red"/></svg>"""),
        )

        assertTrue(outcome is CutOutcome.Manual)
        outcome as CutOutcome.Manual
        assertEquals(1024, outcome.edit.width)
        assertEquals(1024, outcome.edit.height)
        coVerify(exactly = 1) { remover.cut(any()) }
    }

    @Test
    fun `rasterized SVG dimensions are scaled to the maximum supported side`() = runTest {
        val remover = mockk<BackgroundRemover>()
        coEvery { remover.prepare() } returns Result.success(Unit)
        coEvery { remover.cut(any()) } returns Removal.SubjectNotFound
        val listener = RecordingListener()

        val outcome = cutter(remover).cut(
            IncomingImage.Svg("""<svg xmlns="http://www.w3.org/2000/svg" width="4096" height="2"><circle cx="2" cy="1" r="1"/></svg>"""),
            listener,
        )

        assertTrue(outcome is CutOutcome.Manual)
        coVerify(exactly = 1) { remover.cut(match { it.width == 2048 && it.height == 1 }) }
        assertEquals(listOf(8, 18, 28, 92, 100), listener.progress)
    }

    @Test
    fun `cut fixture decodes the bundled sample and delegates to the regular cut pipeline`() = runTest {
        val remover = mockk<BackgroundRemover>()
        coEvery { remover.prepare() } returns Result.success(Unit)
        coEvery { remover.cut(any()) } returns Removal.SubjectNotFound
        val listener = RecordingListener()

        val outcome = cutter(remover).cutFixture(listener)

        assertTrue(outcome is CutOutcome.Manual)
        assertTrue(File(listener.previews.single()).isFile)
        assertEquals(listOf(8, 18, 28, 92, 100), listener.progress)
        coVerify(exactly = 1) { remover.prepare() }
        coVerify(exactly = 1) { remover.cut(match { it.width == 128 && it.height == 128 }) }
    }

    @Test
    fun `fixture overload defaults to no listener and still stores a manual edit`() = runTest {
        val remover = mockk<BackgroundRemover>()
        coEvery { remover.prepare() } returns Result.success(Unit)
        coEvery { remover.cut(any()) } returns Removal.SubjectNotFound

        val outcome = cutter(remover).cutFixture()

        assertTrue(outcome is CutOutcome.Manual)
        outcome as CutOutcome.Manual
        assertTrue(File(outcome.edit.originalPath).isFile)
        assertTrue(File(outcome.edit.cutoutPath).isFile)
        coVerify(exactly = 1) { remover.cut(match { it.width == 128 && it.height == 128 }) }
    }

    @Test
    fun `preview cache keeps the eight newest stale previews before adding the new preview`() = runTest {
        val cache = app.cacheDir
        cache.listFiles()?.filter { it.name.startsWith("cut-source-") }?.forEach { it.delete() }
        val stale = (0 until 10).map { index ->
            File(cache, "cut-source-stale-$index.jpg").apply {
                writeBytes(byteArrayOf(index.toByte()))
                assertTrue(setLastModified(1_700_000_000_000L + index))
            }
        }
        val remover = mockk<BackgroundRemover>()
        coEvery { remover.prepare() } returns Result.success(Unit)
        coEvery { remover.cut(any()) } returns Removal.SubjectNotFound
        val listener = RecordingListener()

        val outcome = cutter(remover).cut(IncomingImage.Raster(sourcePng()), listener)

        assertTrue(outcome is CutOutcome.Manual)
        assertTrue(stale.take(2).none { it.exists() })
        assertTrue(stale.drop(2).all { it.exists() })
        assertTrue(File(listener.previews.single()).isFile)
    }

    @Test
    fun `broken remover result fails without publishing clipboard data`() = runTest {
        val remover = mockk<BackgroundRemover>()
        coEvery { remover.prepare() } returns Result.success(Unit)
        coEvery { remover.cut(any()) } returns Removal.Broken("test")
        val clipboard = mockk<ClipboardImages>(relaxed = true)
        val outcome = cutter(remover, clipboard).cut(IncomingImage.Raster(sourcePng()))
        assertEquals(CutOutcome.Failed(Messages.READ_FAILED), outcome)
        verify(exactly = 0) { clipboard.publish(any(), any()) }
    }

    @Test
    fun `a concurrent request reports busy and cancellation releases the cutter`() = runTest {
        val remover = mockk<BackgroundRemover>()
        val entered = CompletableDeferred<Unit>()
        coEvery { remover.prepare() } coAnswers {
            entered.complete(Unit)
            kotlinx.coroutines.awaitCancellation()
        }
        val subject = cutter(remover)
        val first = async { subject.cut(IncomingImage.Raster(sourcePng())) }
        runCurrent()
        entered.await()
        assertEquals(CutOutcome.Failed(Messages.BUSY), subject.cut(IncomingImage.Invalid("other")))
        first.cancelAndJoin()
        val afterCancel = subject.cut(IncomingImage.Invalid("after"))
        assertEquals(CutOutcome.Failed("after"), afterCancel)
        assertFalse(first.isActive)
    }

    @Test
    fun `cancellation during model inference recycles input and releases the cutter`() = runTest {
        val remover = mockk<BackgroundRemover>()
        val entered = CompletableDeferred<Bitmap>()
        coEvery { remover.prepare() } returns Result.success(Unit)
        coEvery { remover.cut(any()) } coAnswers {
            entered.complete(firstArg())
            kotlinx.coroutines.awaitCancellation()
        }
        val subject = cutter(remover)
        val cut = async { subject.cut(IncomingImage.Raster(sourcePng())) }
        runCurrent()
        val inferenceInput = entered.await()

        cut.cancelAndJoin()

        assertTrue(inferenceInput.isRecycled)
        assertEquals(CutOutcome.Failed("available after cancellation"), subject.cut(IncomingImage.Invalid("available after cancellation")))
        coVerify(exactly = 1) { remover.cut(any()) }
    }

    @Test
    fun `prepare returns remover result without starting an image operation`() = runTest {
        val remover = mockk<BackgroundRemover>()
        val result = Result.failure<Unit>(IllegalStateException("not ready"))
        coEvery { remover.prepare() } returns result
        assertEquals(result, cutter(remover).prepare())
        coVerify(exactly = 1) { remover.prepare() }
    }

    private class RecordingListener : CutListener {
        val progress = mutableListOf<Int>()
        val previews = mutableListOf<String>()
        override fun onProgress(percent: Int) { progress += percent }
        override fun onPreview(path: String) { previews += path }
    }
}
