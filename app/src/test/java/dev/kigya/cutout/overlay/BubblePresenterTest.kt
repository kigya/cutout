package dev.kigya.cutout.overlay

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import dev.kigya.cutout.MainActivity
import dev.kigya.cutout.cutout.CutListener
import dev.kigya.cutout.cutout.CutOutcome
import dev.kigya.cutout.cutout.ImageCutter
import dev.kigya.cutout.data.ClipboardImages
import dev.kigya.cutout.data.ClipboardLook
import dev.kigya.cutout.data.EditFiles
import dev.kigya.cutout.data.EditSessions
import dev.kigya.cutout.data.IncomingImage
import dev.kigya.cutout.domain.Messages
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration.Companion.seconds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class BubblePresenterTest {
    private lateinit var dispatcher: TestDispatcher

    @Before fun setUp() {
        dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() { Dispatchers.resetMain() }

    private data class Fixture(
        val presenter: BubblePresenter,
        val context: Context,
        val cutter: ImageCutter,
        val sessions: EditSessions,
        val picks: PickBus,
        val reads: ClipboardBus,
        val images: ClipboardImages,
    )

    private class RecordingContext(base: Context, private val failure: Exception? = null) : ContextWrapper(base) {
        val started = mutableListOf<Intent>()
        override fun startActivity(intent: Intent) {
            started += intent
            failure?.let { throw it }
        }
    }

    private fun fixture(
        context: Context = RuntimeEnvironment.getApplication(),
        hasPicture: Boolean = true,
    ): Fixture {
        val cutter = mockk<ImageCutter>()
        val sessions = mockk<EditSessions>(relaxed = true)
        val picks = PickBus()
        val reads = ClipboardBus()
        val images = mockk<ClipboardImages>()
        every { images.hasPicture() } returns hasPicture
        every { sessions.pending } returns null
        val presenter = BubblePresenter(context, cutter, sessions, picks, reads, images)
        return Fixture(presenter, context, cutter, sessions, picks, reads, images)
    }

    private suspend fun Fixture.awaitState(predicate: (BubbleUiState) -> Boolean) = withContext(Dispatchers.IO) {
        withTimeout(5.seconds) { presenter.state.first(predicate) }
    }

    @Test
    fun `toggle dismiss anchor and again update visible bubble state`() = runTest {
        val f = fixture(hasPicture = true)
        assertEquals(BubbleUiState(), f.presenter.state.value)
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        assertTrue(f.presenter.state.value.expanded)
        assertTrue(f.presenter.state.value.clipboardReady)
        f.presenter.setAnchorEnd(false)
        assertFalse(f.presenter.state.value.anchorEnd)
        f.presenter.onEvent(BubbleUiEvent.Dismiss)
        assertFalse(f.presenter.state.value.expanded)
        f.presenter.onEvent(BubbleUiEvent.Again)
        assertEquals(BubblePhase.Idle, f.presenter.state.value.phase)
        assertNull(f.presenter.state.value.message)
        assertEquals(0, f.presenter.state.value.percent)
        assertFalse(f.presenter.state.value.editReady)
        assertFalse(f.presenter.state.value.rasterizedSvg)
    }

    @Test
    fun `toggle collapses an open bubble and clipboard probe failures leave it usable`() = runTest {
        val f = fixture()
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        assertTrue(f.presenter.state.value.expanded)
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        assertFalse(f.presenter.state.value.expanded)

        every { f.images.hasPicture() } throws IllegalStateException("clipboard service unavailable")
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        assertTrue(f.presenter.state.value.expanded)
        assertFalse(f.presenter.state.value.clipboardReady)
        assertEquals(BubblePhase.Idle, f.presenter.state.value.phase)
    }

    @Test
    fun `explicit collapse is idempotent and preserves the completed result`() = runTest {
        val f = fixture()
        f.presenter.onEvent(BubbleUiEvent.Collapse)
        val unchanged = f.presenter.state.value
        assertEquals(0, unchanged.revision)

        f.presenter.onEvent(BubbleUiEvent.Toggle)
        coEvery { f.cutter.cut(any(), any()) } returns CutOutcome.CopiedRaster(
            message = Messages.COPIED,
            previewPath = "/cache/result.png",
            edit = EditFiles("/cache/original.png", "/cache/result.png", 2, 2),
            rasterizedSvg = false,
        )
        f.picks.offer(IncomingImage.Raster(byteArrayOf(1)), PickBus.TARGET_BUBBLE)
        f.awaitState { it.phase == BubblePhase.Copied }
        val result = f.presenter.state.value
        f.presenter.onEvent(BubbleUiEvent.Collapse)
        assertFalse(f.presenter.state.value.expanded)
        assertEquals(result.phase, f.presenter.state.value.phase)
        assertEquals(result.previewPath, f.presenter.state.value.previewPath)
        assertEquals(result.message, f.presenter.state.value.message)
        assertEquals(result.percent, f.presenter.state.value.percent)
        val revision = f.presenter.state.value.revision
        f.presenter.onEvent(BubbleUiEvent.Collapse)
        assertEquals("a repeated collapse must not publish another state", revision, f.presenter.state.value.revision)
    }

    @Test
    fun `refine collapses only after launch and leaves errors visible`() = runTest {
        val files = EditFiles("/tmp/original.png", "/tmp/result.png", 10, 10)
        val successContext = RecordingContext(RuntimeEnvironment.getApplication())
        val success = fixture(successContext)
        every { success.sessions.pending } returns files
        success.presenter.onEvent(BubbleUiEvent.Toggle)
        success.presenter.onEvent(BubbleUiEvent.Refine)
        assertFalse(success.presenter.state.value.expanded)
        assertTrue(success.presenter.state.value.editReady)
        assertNull(success.presenter.state.value.message)
        assertEquals(1, successContext.started.size)

        val failureContext = RecordingContext(RuntimeEnvironment.getApplication(), ActivityNotFoundException("missing"))
        val failure = fixture(failureContext)
        every { failure.sessions.pending } returns files
        failure.presenter.onEvent(BubbleUiEvent.Toggle)
        failure.presenter.onEvent(BubbleUiEvent.Refine)
        assertTrue(failure.presenter.state.value.expanded)
        assertFalse(failure.presenter.state.value.editReady)
        assertEquals(Messages.READ_FAILED, failure.presenter.state.value.message)
        verify { failure.sessions.openRequested = false }
    }

    @Test
    fun `picker and clipboard launch failures return visible failed state`() = runTest {
        val context = RecordingContext(RuntimeEnvironment.getApplication(), ActivityNotFoundException("missing"))
        val f = fixture(context)
        f.presenter.onEvent(BubbleUiEvent.FromClipboard)
        assertEquals(BubblePhase.Failed, f.presenter.state.value.phase)
        assertFalse(f.presenter.state.value.readingClipboard)
        assertEquals(Messages.PICKER_FAILED, f.presenter.state.value.message)
        f.presenter.onEvent(BubbleUiEvent.FromGallery)
        assertFalse(f.presenter.state.value.expanded)
        assertEquals(Messages.PICKER_FAILED, f.presenter.state.value.message)
        f.presenter.onEvent(BubbleUiEvent.FromFile)
        assertEquals(Messages.PICKER_FAILED, f.presenter.state.value.message)
        assertEquals(3, context.started.size)
    }

    @Test
    fun `gallery and file actions launch their picker modes for the bubble`() = runTest {
        val context = RecordingContext(RuntimeEnvironment.getApplication())
        val f = fixture(context)

        f.presenter.onEvent(BubbleUiEvent.FromGallery)
        val gallery = context.started.last()
        assertEquals(PickerActivity.MODE_GALLERY, gallery.getStringExtra(PickerActivity.EXTRA_MODE))
        assertEquals(PickBus.TARGET_BUBBLE, gallery.getStringExtra(PickerActivity.EXTRA_TARGET))
        assertFalse(f.presenter.state.value.expanded)
        assertNull(f.presenter.state.value.message)

        f.presenter.onEvent(BubbleUiEvent.FromFile)
        val file = context.started.last()
        assertEquals(PickerActivity.MODE_FILE, file.getStringExtra(PickerActivity.EXTRA_MODE))
        assertEquals(PickBus.TARGET_BUBBLE, file.getStringExtra(PickerActivity.EXTRA_TARGET))
        assertEquals(2, context.started.size)
        assertNull(f.presenter.state.value.message)
    }

    @Test
    fun `refine opens only when edit files are pending`() = runTest {
        val context = RecordingContext(RuntimeEnvironment.getApplication())
        val f = fixture(context)
        f.presenter.onEvent(BubbleUiEvent.Refine)
        assertTrue(f.presenter.state.value.expanded)
        assertFalse(f.presenter.state.value.editReady)
        assertEquals(Messages.READ_FAILED, f.presenter.state.value.message)
        val files = EditFiles("original.png", "cutout.png", 20, 10)
        every { f.sessions.pending } returns files
        f.presenter.onEvent(BubbleUiEvent.Refine)
        assertTrue(f.presenter.state.value.editReady)
        verify(exactly = 1) { f.sessions.requestOpen() }
        assertTrue(context.started.single().getBooleanExtra(MainActivity.EXTRA_EDIT, false))
    }

    @Test
    fun `clipboard empty unreadable found peek and canceled picker preserve intended state`() = runTest {
        val f = fixture()
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        f.reads.offer(ClipboardRead(ClipboardLook.Empty, consume = false))
        runCurrent()
        assertFalse(f.presenter.state.value.readingClipboard)
        assertFalse(f.presenter.state.value.clipboardReady)
        assertEquals(BubblePhase.Idle, f.presenter.state.value.phase)

        f.reads.offer(ClipboardRead(ClipboardLook.Found(IncomingImage.Invalid("idle peek")), consume = false))
        runCurrent()
        assertTrue(f.presenter.state.value.clipboardReady)
        assertEquals(BubblePhase.Idle, f.presenter.state.value.phase)

        f.reads.offer(ClipboardRead(ClipboardLook.Unreadable, consume = false))
        runCurrent()
        assertEquals(BubblePhase.Failed, f.presenter.state.value.phase)
        assertEquals(Messages.READ_FAILED, f.presenter.state.value.message)
        f.presenter.onEvent(BubbleUiEvent.Dismiss)
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        assertTrue(f.presenter.state.value.expanded)
        assertEquals(BubblePhase.Failed, f.presenter.state.value.phase)
        assertEquals(Messages.READ_FAILED, f.presenter.state.value.message)
        f.reads.offer(ClipboardRead(ClipboardLook.Found(IncomingImage.Invalid("peek")), consume = false))
        runCurrent()
        assertEquals(BubblePhase.Idle, f.presenter.state.value.phase)
        assertNull(f.presenter.state.value.message)
        f.picks.cancel(PickBus.TARGET_BUBBLE)
        runCurrent()
        assertEquals(BubblePhase.Failed, f.presenter.state.value.phase)
        assertNull(f.presenter.state.value.message)
        assertTrue(f.presenter.state.value.expanded)
    }

    @Test
    fun `picker result can replace a pending clipboard probe without a second cut`() = runTest {
        val context = RecordingContext(RuntimeEnvironment.getApplication())
        val f = fixture(context)
        coEvery { f.cutter.cut(any(), any()) } returns CutOutcome.CopiedSvg(Messages.COPIED)

        f.presenter.onEvent(BubbleUiEvent.FromClipboard)
        assertTrue(f.presenter.state.value.readingClipboard)
        f.presenter.onEvent(BubbleUiEvent.FromGallery)
        assertFalse(f.presenter.state.value.readingClipboard)
        f.picks.offer(IncomingImage.Svg("<svg/>"), PickBus.TARGET_BUBBLE)
        f.awaitState { it.phase == BubblePhase.Copied }

        assertEquals(Messages.COPIED, f.presenter.state.value.message)
        assertEquals(2, context.started.size)
        coVerify(exactly = 1) { f.cutter.cut(any(), any()) }
    }

    @Test
    fun `cancellation from cutter restores ability to process the next pick`() = runTest {
        val f = fixture()
        coEvery { f.cutter.cut(any(), any()) } throws kotlinx.coroutines.CancellationException("cancelled by caller")
        f.picks.offer(IncomingImage.Raster(byteArrayOf(1)), PickBus.TARGET_BUBBLE)
        f.awaitState { it.phase == BubblePhase.Working }

        coEvery { f.cutter.cut(any(), any()) } returns CutOutcome.CopiedSvg(Messages.COPIED)
        f.picks.offer(IncomingImage.Svg("<svg/>"), PickBus.TARGET_BUBBLE)
        f.awaitState { it.phase == BubblePhase.Copied }
        assertEquals(Messages.COPIED, f.presenter.state.value.message)
        assertEquals(100, f.presenter.state.value.percent)
    }

    @Test
    fun `clipboard consume empty reports failure while found consume starts cut`() = runTest {
        val context = RecordingContext(RuntimeEnvironment.getApplication())
        val f = fixture(context)
        f.presenter.onEvent(BubbleUiEvent.FromClipboard)
        f.reads.offer(ClipboardRead(ClipboardLook.Empty, consume = true))
        runCurrent()
        assertEquals(BubblePhase.Failed, f.presenter.state.value.phase)
        assertEquals(Messages.EMPTY_CLIPBOARD, f.presenter.state.value.message)
        coEvery { f.cutter.cut(any(), any()) } returns CutOutcome.Failed("done")
        f.presenter.onEvent(BubbleUiEvent.FromClipboard)
        f.reads.offer(ClipboardRead(ClipboardLook.Found(IncomingImage.Invalid("consume")), consume = true))
        runCurrent()
        f.awaitState { it.phase == BubblePhase.Failed && it.message == "done" }
        coVerify(exactly = 1) { f.cutter.cut(any(), any()) }
        assertEquals(2, context.started.size)
        assertEquals(BubblePhase.Failed, f.presenter.state.value.phase)
        assertEquals("done", f.presenter.state.value.message)
    }

    @Test
    fun `cut publishes progress preview rasterized result and failure outcome`() = runTest {
        val f = fixture()
        val copied = CutOutcome.CopiedRaster(
            message = Messages.SVG_AS_PNG,
            previewPath = "/cache/preview.jpg",
            edit = EditFiles("original.png", "cutout.png", 2, 2),
            rasterizedSvg = true,
        )
        coEvery { f.cutter.cut(any(), any()) } coAnswers {
            secondArg<CutListener>().apply {
                onPreview("/cache/preview.jpg")
                onProgress(63)
            }
            copied
        }
        f.picks.offer(IncomingImage.Raster(byteArrayOf(1)), PickBus.TARGET_BUBBLE)
        f.awaitState { it.phase == BubblePhase.Copied }
        assertEquals(BubblePhase.Copied, f.presenter.state.value.phase)
        assertEquals("ready", f.presenter.state.value.stage)
        assertEquals(100, f.presenter.state.value.percent)
        assertEquals("/cache/preview.jpg", f.presenter.state.value.previewPath)
        assertTrue(f.presenter.state.value.rasterizedSvg)
        assertTrue(f.presenter.state.value.editReady)
        coVerify(exactly = 1) { f.cutter.cut(any(), any()) }

        coEvery { f.cutter.cut(any(), any()) } returns CutOutcome.Failed("model")
        f.picks.offer(IncomingImage.Invalid("bad"), PickBus.TARGET_BUBBLE)
        f.awaitState { it.phase == BubblePhase.Failed && it.message == "model" }
        assertEquals(BubblePhase.Failed, f.presenter.state.value.phase)
        assertEquals("model", f.presenter.state.value.message)
        assertFalse(f.presenter.state.value.editReady)
    }

    @Test
    fun `cut progress moves through preparing inference and exporting before ready`() = runTest {
        val f = fixture()
        coEvery { f.cutter.cut(any(), any()) } coAnswers {
            val listener = secondArg<CutListener>()
            listener.onProgress(10)
            listener.onProgress(50)
            listener.onProgress(98)
            CutOutcome.CopiedSvg(Messages.COPIED)
        }
        f.picks.offer(IncomingImage.Svg("<svg/>"), PickBus.TARGET_BUBBLE)
        f.awaitState { it.phase == BubblePhase.Copied }

        assertEquals("ready", f.presenter.state.value.stage)
        assertEquals(100, f.presenter.state.value.percent)
        assertEquals(Messages.COPIED, f.presenter.state.value.message)
    }

    @Test
    fun `manual outcome offers editor refine and thrown cut failures return to failed phase`() = runTest {
        val f = fixture()
        coEvery { f.cutter.cut(any(), any()) } returns CutOutcome.Manual(
            Messages.MANUAL,
            EditFiles("original.png", "cutout.png", 4, 3),
        )
        f.picks.offer(IncomingImage.Raster(byteArrayOf(1)), PickBus.TARGET_BUBBLE)
        f.awaitState { it.phase == BubblePhase.Failed && it.stage == "ready" }
        assertEquals(BubblePhase.Failed, f.presenter.state.value.phase)
        assertEquals("ready", f.presenter.state.value.stage)
        assertEquals("cutout.png", f.presenter.state.value.previewPath)
        assertTrue(f.presenter.state.value.editReady)
        assertEquals(100, f.presenter.state.value.percent)

        coEvery { f.cutter.cut(any(), any()) } throws IllegalStateException("unexpected")
        f.picks.offer(IncomingImage.Raster(byteArrayOf(1)), PickBus.TARGET_BUBBLE)
        f.awaitState { it.phase == BubblePhase.Failed && it.stage == "failed" }
        assertEquals(BubblePhase.Failed, f.presenter.state.value.phase)
        assertEquals("failed", f.presenter.state.value.stage)
        assertEquals(Messages.READ_FAILED, f.presenter.state.value.message)
        assertFalse(f.presenter.state.value.editReady)
    }

    @Test
    fun `second pick while cutter is suspended is ignored until first completes`() = runTest {
        val f = fixture()
        val started = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<CutOutcome>()
        coEvery { f.cutter.cut(any(), any()) } coAnswers {
            started.complete(Unit)
            finish.await()
        }
        f.picks.offer(IncomingImage.Raster(byteArrayOf(1)), PickBus.TARGET_BUBBLE)
        runCurrent()
        started.await()
        f.awaitState { it.phase == BubblePhase.Working }
        f.picks.offer(IncomingImage.Raster(byteArrayOf(2)), PickBus.TARGET_BUBBLE)
        runCurrent()
        coVerify(exactly = 1) { f.cutter.cut(any(), any()) }
        finish.complete(CutOutcome.CopiedSvg(Messages.COPIED))
        f.awaitState { it.phase == BubblePhase.Copied }
        assertEquals(BubblePhase.Copied, f.presenter.state.value.phase)
        assertEquals(100, f.presenter.state.value.percent)
    }
}
