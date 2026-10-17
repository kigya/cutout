package dev.kigya.cutout.overlay

import android.app.Activity
import android.app.Application
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import dagger.hilt.android.internal.managers.ActivityComponentManager
import dev.kigya.cutout.data.ClipboardImages
import dev.kigya.cutout.data.ClipboardLook
import dev.kigya.cutout.data.GalleryStore
import dev.kigya.cutout.data.IncomingImage
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import java.io.File
import java.time.Duration
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.yield
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

/** Exercise activity lifecycle and OS result delivery with the DI boundary supplied by the test. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ForegroundActivitiesTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun injected(activity: Activity) {
        ReflectionHelpers.setField(activity, "injected", true)
        ReflectionHelpers.setField(activity, "componentManager", mockk<ActivityComponentManager>(relaxed = true))
    }
    private fun awaitFinished(activity: Activity) {
        val deadline = System.nanoTime() + 3_000_000_000L
        while (!activity.isFinishing && System.nanoTime() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        assertTrue("foreground bridge must finish", activity.isFinishing)
    }
    @Test fun `requestless focused clipboard read reaches only the Bubble subscriber`() = runBlocking<Unit> {
        val bus = ClipboardBus()
        val reads = mutableListOf<ClipboardRead>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { bus.reads.collect { reads += it } }
        val clipboard = mockk<ClipboardImages>()
        val look = ClipboardLook.Found(IncomingImage.Raster(byteArrayOf(4)))
        every { clipboard.peek() } returns look
        val controller = Robolectric.buildActivity(ClipboardActivity::class.java)
        val activity = controller.get()
        injected(activity); activity.clipboard = clipboard; activity.bus = bus
        controller.create()
        activity.onWindowFocusChanged(true)
        yield()
        assertEquals(listOf(ClipboardRead(look, false)), reads)
        assertTrue(activity.isFinishing)
        collector.cancel()
        controller.destroy()
    }
    @Test fun `closing clipboard bridge before focus does not read private clipboard data`() {
        val controller = Robolectric.buildActivity(ClipboardActivity::class.java)
        val activity = controller.get()
        val clipboard = mockk<ClipboardImages>()
        injected(activity); activity.clipboard = clipboard; activity.bus = ClipboardBus()
        controller.create()
        activity.finish()
        activity.onWindowFocusChanged(true)
        verify(exactly = 0) { clipboard.peek() }
        controller.destroy()
    }
    @Test fun `empty clipboard in an actually focused window returns without retry delay`() = runBlocking<Unit> {
        val controller = Robolectric.buildActivity(ClipboardActivity::class.java)
        val activity = controller.get()
        val bus = ClipboardBus()
        val clipboard = mockk<ClipboardImages>()
        every { clipboard.peek() } returns ClipboardLook.Empty
        injected(activity); activity.clipboard = clipboard; activity.bus = bus
        controller.create().start().resume().visible().windowFocusChanged(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        // Resume probes before focus; the focus callback completes immediately without waiting 200 ms.
        verify(exactly = 2) { clipboard.peek() }
        controller.pause().stop().destroy()
    }
    @Test fun `stale picker cannot open a new system picker or settle a current request`() = runBlocking<Unit> {
        val bus = PickBus()
        val pending = bus.awaitEditor()
        val intent = Intent(app, PickerActivity::class.java)
            .putExtra(PickerActivity.EXTRA_TARGET, PickBus.TARGET_EDITOR)
            .putExtra(PickerActivity.EXTRA_REQUEST, "stale")
        val controller = Robolectric.buildActivity(PickerActivity::class.java, intent)
        val activity = controller.get()
        injected(activity); activity.bus = bus
        controller.create()
        assertTrue(activity.isFinishing)
        assertFalse(pending.isCompleted)
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        bus.cancel(PickBus.TARGET_EDITOR)
        assertNull(withTimeout(3000) { pending.await() })
        controller.destroy()
    }
    @Test fun `file picker cancellation completes the matching editor request`() = runBlocking<Unit> {
        val bus = PickBus()
        val pending = bus.awaitEditor()
        val intent = Intent(app, PickerActivity::class.java)
            .putExtra(PickerActivity.EXTRA_MODE, PickerActivity.MODE_FILE)
            .putExtra(PickerActivity.EXTRA_TARGET, PickBus.TARGET_EDITOR)
            .putExtra(PickerActivity.EXTRA_REQUEST, bus.editorRequestId)
        val controller = Robolectric.buildActivity(PickerActivity::class.java, intent)
        val activity = controller.get()
        injected(activity); activity.bus = bus
        controller.create().start().resume()
        val launch = shadowOf(activity).nextStartedActivityForResult
        assertEquals(Intent.ACTION_OPEN_DOCUMENT, launch.intent.action)
        assertArrayEquals(arrayOf("image/*", "image/svg+xml"), launch.intent.getStringArrayExtra(Intent.EXTRA_MIME_TYPES))
        shadowOf(activity).receiveResult(launch.intent, Activity.RESULT_CANCELED, null)
        assertNull(withTimeout(3000) { pending.await() })
        assertTrue(activity.isFinishing)
        controller.pause().stop().destroy()
    }
    @Test fun `restored picker does not launch a second external activity`() {
        val controller = Robolectric.buildActivity(PickerActivity::class.java)
        val activity = controller.get()
        injected(activity); activity.bus = PickBus()
        controller.create(Bundle())
        assertNull(shadowOf(activity).nextStartedActivityForResult)
        controller.destroy()
    }
    @Test fun `clipboard is read only once in focused bridge and settles only matching request`() = runBlocking<Unit> {
        val bus = ClipboardBus()
        val pending = bus.awaitConsume()
        val clipboard = mockk<ClipboardImages>()
        val expected = ClipboardLook.Found(IncomingImage.Raster(byteArrayOf(1)))
        every { clipboard.peek() } returns expected
        val intent = Intent(app, ClipboardActivity::class.java)
            .putExtra(ClipboardActivity.EXTRA_REQUEST, bus.consumeRequestId)
            .putExtra(ClipboardActivity.EXTRA_CONSUME, true)
        val controller = Robolectric.buildActivity(ClipboardActivity::class.java, intent)
        val activity = controller.get()
        injected(activity); activity.clipboard = clipboard; activity.bus = bus
        controller.create()
        activity.onWindowFocusChanged(true)
        activity.onWindowFocusChanged(false)
        activity.onWindowFocusChanged(true)
        assertEquals(expected, withTimeout(3000) { pending.await() })
        verify(exactly = 1) { clipboard.peek() }
        assertTrue(activity.isFinishing)
        controller.destroy()
    }
    @Test fun `clipboard denial is reported as unreadable and stale request never reads clipboard`() = runBlocking<Unit> {
        val bus = ClipboardBus()
        val pending = bus.awaitConsume()
        val clipboard = mockk<ClipboardImages>()
        every { clipboard.peek() } throws SecurityException("focus denied")
        val intent = Intent(app, ClipboardActivity::class.java).putExtra(ClipboardActivity.EXTRA_REQUEST, bus.consumeRequestId)
        val controller = Robolectric.buildActivity(ClipboardActivity::class.java, intent)
        val activity = controller.get()
        injected(activity); activity.clipboard = clipboard; activity.bus = bus
        controller.create()
        activity.onWindowFocusChanged(true)
        assertEquals(ClipboardLook.Unreadable, withTimeout(3000) { pending.await() })
        controller.destroy()
        val staleController = Robolectric.buildActivity(ClipboardActivity::class.java, intent)
        val stale = staleController.get()
        injected(stale); stale.clipboard = clipboard; stale.bus = bus
        staleController.create().start().resume()
        shadowOf(Looper.getMainLooper()).idle()
        verify(exactly = 1) { clipboard.peek() }
        assertTrue(stale.isFinishing)
        staleController.pause().stop().destroy()
    }
    @Test fun `unfocused empty clipboard retries are bounded and do not hang the request`() = runBlocking<Unit> {
        val bus = ClipboardBus()
        val pending = bus.awaitConsume()
        val clipboard = mockk<ClipboardImages>()
        every { clipboard.peek() } returns ClipboardLook.Empty
        val intent = Intent(app, ClipboardActivity::class.java).putExtra(ClipboardActivity.EXTRA_REQUEST, bus.consumeRequestId)
        val controller = Robolectric.buildActivity(ClipboardActivity::class.java, intent)
        val activity = controller.get()
        injected(activity); activity.clipboard = clipboard; activity.bus = bus
        controller.create().start().resume().visible()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2))
        assertEquals(ClipboardLook.Empty, withTimeout(3000) { pending.await() })
        verify(exactly = 8) { clipboard.peek() }
        controller.pause().stop().destroy()
    }
    @Test fun `legacy export rejects outside cache paths and absent source before permission request`() = runBlocking<Unit> {
        val outside = File(app.filesDir, "private.png").apply { writeText("private") }
        val directory = File(app.cacheDir, "not-file").apply { mkdirs() }
        for (path in listOf(null, outside.path, directory.path, File(app.cacheDir, "missing.png").path)) {
            val pending = LegacyExportActivity.reserve()!!
            val intent = Intent(app, LegacyExportActivity::class.java).putExtra("path", path)
            val controller = Robolectric.buildActivity(LegacyExportActivity::class.java, intent)
            val activity = controller.get()
            injected(activity); activity.gallery = mockk<GalleryStore>(relaxed = true)
            controller.create()
            assertEquals("failed", withTimeout(3000) { pending.await() })
            assertTrue(activity.isFinishing)
            assertNull(shadowOf(activity).nextStartedActivityForResult)
            controller.destroy()
        }
    }
}
