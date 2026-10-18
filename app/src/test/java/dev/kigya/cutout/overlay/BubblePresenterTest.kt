package dev.kigya.cutout.overlay

import android.app.Application
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import dev.kigya.cutout.MainActivity
import dev.kigya.cutout.MainActivityState
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
    private val presenters = mutableListOf<BubblePresenter>()

    @Before fun setUp() {
        dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
    }

    @After fun tearDown() {
        presenters.forEach(BubblePresenter::stop)
        Dispatchers.resetMain()
    }

    private data class Fixture(
        val presenter: BubblePresenter,
        val context: Context,
        val cutter: ImageCutter,
        val sessions: EditSessions,
        val picks: PickBus,
        val reads: ClipboardBus,
        val images: ClipboardImages,
        val mainActivity: MainActivityState,
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
        val mainActivity = MainActivityState()
        val presenter = BubblePresenter(context, cutter, sessions, picks, reads, images, mainActivity)
        presenters += presenter
        presenter.start()
        return Fixture(presenter, context, cutter, sessions, picks, reads, images, mainActivity)
    }

    @Test
    fun `default Activity state supports a standalone presenter`() = runTest {
        val presenter = BubblePresenter(
            RuntimeEnvironment.getApplication(),
            mockk(),
            mockk(relaxed = true),
            PickBus(),
            ClipboardBus(),
            mockk<ClipboardImages> { every { hasPicture() } returns false },
        )
        presenters += presenter

        presenter.start()
        presenter.onEvent(BubbleUiEvent.Toggle)

        assertTrue(presenter.state.value.expanded)
        assertTrue(presenter.state.value.clipboardReady)
    }

    private fun Fixture.pick(image: IncomingImage) {
        presenter.onEvent(BubbleUiEvent.FromGallery)
        picks.offer(image, PickBus.TARGET_BUBBLE, picks.bubbleRequestId)
    }

    private suspend fun Fixture.awaitState(predicate: (BubbleUiState) -> Boolean) = withContext(Dispatchers.IO) {
        withTimeout(5.seconds) { presenter.state.first(predicate) }
    }

    @Test
    fun `main Activity boundaries collapse panel and prohibit in-app expansion`() = runTest {
        val f = fixture()
        val activity = android.app.Activity()
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        assertTrue(f.presenter.state.value.expanded)
        f.mainActivity.started(activity)
        runCurrent()
        assertFalse(f.presenter.state.value.expanded)
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        assertFalse(f.presenter.state.value.expanded)
        assertNull(f.presenter.state.value.message)
        f.mainActivity.stopped(activity)
        runCurrent()
        assertFalse(f.presenter.state.value.expanded)
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        assertTrue(f.presenter.state.value.expanded)
        assertNull(f.presenter.state.value.message)
    }

    @Test
    fun `return during processing keeps its result collapsed and available outside`() = runTest {
        val f = fixture()
        val activity = android.app.Activity()
        val began = CompletableDeferred<Unit>()
        val finish = CompletableDeferred<CutOutcome>()
        coEvery { f.cutter.cut(any(), any()) } coAnswers { began.complete(Unit); finish.await() }
        f.pick(IncomingImage.Svg("<svg/>"))
        began.await()
        f.mainActivity.started(activity)
        runCurrent()
        assertFalse(f.presenter.state.value.expanded)
        finish.complete(CutOutcome.CopiedRaster(Messages.COPIED, "/result.png", EditFiles("/source.png", "/result.png", 2, 2), false))
        f.awaitState { it.phase == BubblePhase.Copied }
        assertFalse(f.presenter.state.value.expanded)
        assertEquals("/result.png", f.presenter.state.value.previewPath)
        f.mainActivity.stopped(activity)
        runCurrent()
        assertFalse(f.presenter.state.value.expanded)
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        assertTrue(f.presenter.state.value.expanded)
        assertEquals("/result.png", f.presenter.state.value.previewPath)
    }

    @Test
    fun `collapse and stop discard late clipboard reads and reopening allows a retry`() = runTest {
        val f = fixture()
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        f.presenter.onEvent(BubbleUiEvent.FromClipboard)
        val stale = f.reads.bubbleRequestId
        f.presenter.collapse()
        f.reads.offer(ClipboardRead(ClipboardLook.Found(IncomingImage.Svg("stale")), true, stale))
        runCurrent()
        assertFalse(f.presenter.state.value.readingClipboard)
        coVerify(exactly = 0) { f.cutter.cut(any(), any()) }
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        f.presenter.onEvent(BubbleUiEvent.FromClipboard)
        f.reads.offer(ClipboardRead(ClipboardLook.NoFocus, true, f.reads.bubbleRequestId))
        runCurrent()
        assertEquals(Messages.CLIPBOARD_NO_FOCUS, f.presenter.state.value.message)
        assertFalse(f.presenter.state.value.readingClipboard)
        f.presenter.onEvent(BubbleUiEvent.FromClipboard)
        assertTrue(f.presenter.state.value.readingClipboard)
        f.presenter.stop()
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        assertFalse(f.presenter.state.value.expanded)
        assertNull(f.reads.bubbleRequestId)
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
        assertTrue(f.presenter.state.value.clipboardReady)
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
        f.pick(IncomingImage.Raster(byteArrayOf(1)))
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
    fun `cancelled gallery request returns to a visible failed state`() = runTest {
        val f = fixture(RecordingContext(RuntimeEnvironment.getApplication()))
        f.presenter.onEvent(BubbleUiEvent.FromGallery)
        val requestId = requireNotNull(f.picks.bubbleRequestId)

        f.picks.cancel(PickBus.TARGET_BUBBLE, requestId)
        runCurrent()

        assertEquals(BubblePhase.Failed, f.presenter.state.value.phase)
        assertTrue(f.presenter.state.value.expanded)
        assertFalse(f.presenter.state.value.readingClipboard)
        assertNull(f.presenter.state.value.message)
    }

    @Test
    fun `clipboard timeout and unreadable results report distinct recovery messages`() = runTest {
        val context = RecordingContext(RuntimeEnvironment.getApplication())
        val f = fixture(context)
        f.presenter.onEvent(BubbleUiEvent.FromClipboard)

        dispatcher.scheduler.advanceTimeBy(5_001)
        dispatcher.scheduler.runCurrent()
        assertEquals(Messages.CLIPBOARD_NO_FOCUS, f.presenter.state.value.message)
        assertFalse(f.presenter.state.value.readingClipboard)

        f.presenter.onEvent(BubbleUiEvent.FromClipboard)
        f.reads.offer(ClipboardRead(ClipboardLook.Unreadable, consume = true, requestId = f.reads.bubbleRequestId))
        runCurrent()
        assertEquals(BubblePhase.Failed, f.presenter.state.value.phase)
        assertEquals(Messages.READ_FAILED, f.presenter.state.value.message)
        assertFalse(f.presenter.state.value.clipboardReady)
        assertEquals(2, context.started.size)
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

        f.presenter.collapse()
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
    fun `unrequested clipboard events cannot alter the bubble`() = runTest {
        val f = fixture()
        f.presenter.onEvent(BubbleUiEvent.Toggle)
        val before = f.presenter.state.value
        f.reads.offer(ClipboardRead(ClipboardLook.Unreadable, consume = false))
        runCurrent()
        assertEquals(before, f.presenter.state.value)
        verify(exactly = 0) { f.images.hasPicture() }
    }

    @Test
    fun `collapse cancels clipboard before a new picker request`() = runTest {
        val context = RecordingContext(RuntimeEnvironment.getApplication())
        val f = fixture(context)
        coEvery { f.cutter.cut(any(), any()) } returns CutOutcome.CopiedSvg(Messages.COPIED)
        f.presenter.onEvent(BubbleUiEvent.FromClipboard)
        val stale = f.reads.bubbleRequestId
        f.presenter.collapse()
        f.pick(IncomingImage.Svg("<svg/>"))
        f.reads.offer(ClipboardRead(ClipboardLook.Found(IncomingImage.Svg("stale")), true, stale))
        f.awaitState { it.phase == BubblePhase.Copied }
        assertEquals(Messages.COPIED, f.presenter.state.value.message)
        coVerify(exactly = 1) { f.cutter.cut(any(), any()) }
    }

    @Test
    fun `clipboard consume empty reports failure while found consume starts cut`() = runTest {
        val context = RecordingContext(RuntimeEnvironment.getApplication())
        val f = fixture(context)
        f.presenter.onEvent(BubbleUiEvent.FromClipboard)
        f.reads.offer(ClipboardRead(ClipboardLook.Empty, consume = true, requestId = f.reads.bubbleRequestId))
        runCurrent()
        assertEquals(BubblePhase.Failed, f.presenter.state.value.phase)
        assertEquals(Messages.EMPTY_CLIPBOARD, f.presenter.state.value.message)
        coEvery { f.cutter.cut(any(), any()) } returns CutOutcome.Failed("done")
        f.presenter.onEvent(BubbleUiEvent.FromClipboard)
        f.reads.offer(ClipboardRead(ClipboardLook.Found(IncomingImage.Invalid("consume")), consume = true, requestId = f.reads.bubbleRequestId))
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
        f.pick(IncomingImage.Raster(byteArrayOf(1)))
        f.awaitState { it.phase == BubblePhase.Copied }
        assertEquals(BubblePhase.Copied, f.presenter.state.value.phase)
        assertEquals("ready", f.presenter.state.value.stage)
        assertEquals(100, f.presenter.state.value.percent)
        assertEquals("/cache/preview.jpg", f.presenter.state.value.previewPath)
        assertTrue(f.presenter.state.value.rasterizedSvg)
        assertTrue(f.presenter.state.value.editReady)
        coVerify(exactly = 1) { f.cutter.cut(any(), any()) }

        coEvery { f.cutter.cut(any(), any()) } returns CutOutcome.Failed("model")
        f.pick(IncomingImage.Invalid("bad"))
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
        f.pick(IncomingImage.Svg("<svg/>"))
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
        f.pick(IncomingImage.Raster(byteArrayOf(1)))
        f.awaitState { it.phase == BubblePhase.Failed && it.stage == "ready" }
        assertEquals(BubblePhase.Failed, f.presenter.state.value.phase)
        assertEquals("ready", f.presenter.state.value.stage)
        assertEquals("cutout.png", f.presenter.state.value.previewPath)
        assertTrue(f.presenter.state.value.editReady)
        assertEquals(100, f.presenter.state.value.percent)

        coEvery { f.cutter.cut(any(), any()) } throws IllegalStateException("unexpected")
        f.pick(IncomingImage.Raster(byteArrayOf(1)))
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
        f.pick(IncomingImage.Raster(byteArrayOf(1)))
        runCurrent()
        started.await()
        f.awaitState { it.phase == BubblePhase.Working }
        f.pick(IncomingImage.Raster(byteArrayOf(2)))
        runCurrent()
        coVerify(exactly = 1) { f.cutter.cut(any(), any()) }
        finish.complete(CutOutcome.CopiedSvg(Messages.COPIED))
        f.awaitState { it.phase == BubblePhase.Copied }
        assertEquals(BubblePhase.Copied, f.presenter.state.value.phase)
        assertEquals(100, f.presenter.state.value.percent)
    }
}
