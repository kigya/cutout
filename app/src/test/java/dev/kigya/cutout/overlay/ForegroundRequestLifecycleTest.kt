package dev.kigya.cutout.overlay

import android.app.Activity
import android.app.Application
import android.os.Looper
import dev.kigya.cutout.data.IncomingImage
import dev.kigya.cutout.data.ClipboardLook
import java.lang.ref.WeakReference
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ForegroundRequestLifecycleTest {
    @Test fun `explicit cancellation finishes live owners and repeated cleanup remains harmless`() = runBlocking<Unit> {
        val picker = Robolectric.buildActivity(Activity::class.java).setup()
        val pickBus = PickBus()
        val pick = pickBus.awaitEditor()
        assertTrue(pickBus.attachEditor(picker.get(), pickBus.editorRequestId))
        pickBus.cancel(PickBus.TARGET_EDITOR)
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(pick.await())
        assertTrue(picker.get().isFinishing)
        pickBus.cancel(PickBus.TARGET_EDITOR)
        picker.pause().stop().destroy()

        val reader = Robolectric.buildActivity(Activity::class.java).setup()
        val clipBus = ClipboardBus()
        val read = clipBus.awaitConsume()
        assertTrue(clipBus.attachForeground(reader.get(), clipBus.consumeRequestId))
        clipBus.cancelConsume()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(read.await())
        assertTrue(reader.get().isFinishing)
        clipBus.cancelConsume()
        reader.pause().stop().destroy()
    }
    @Test fun `replacing a picker finishes its old foreground activity and rejects missing request IDs`() = runBlocking<Unit> {
        val bus = PickBus()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val old = bus.awaitEditor()
        assertFalse(bus.attachEditor(activity, null))
        assertTrue(bus.attachEditor(activity, bus.editorRequestId))
        val pending = bus.awaitEditor()
        shadowOf(Looper.getMainLooper()).idle()
        assertNull(old.await())
        assertTrue(activity.isFinishing)
        bus.offer(IncomingImage.Raster(byteArrayOf(1)), PickBus.TARGET_EDITOR)
        assertFalse(pending.isCompleted)
        bus.cancel(PickBus.TARGET_EDITOR, bus.editorRequestId)
        assertNull(pending.await())
        assertFalse(bus.attachEditor(activity, "finished"))
        controller.pause().stop().destroy()
    }
    @Test fun `collected picker owner and missing result slot can be canceled safely`() = runBlocking<Unit> {
        val bus = PickBus()
        val pending = bus.awaitEditor()
        ReflectionHelpers.setField(bus, "editorActivity", WeakReference<Activity>(null))
        bus.cancel(PickBus.TARGET_EDITOR)
        assertNull(pending.await())
        // A duplicated matching callback after a completed request must be harmless.
        val id = "already-completed"
        ReflectionHelpers.setField(bus, "editorRequestId", id)
        val activity = Robolectric.buildActivity(Activity::class.java).get()
        assertFalse(bus.attachEditor(activity, id))
        bus.offer(IncomingImage.Raster(byteArrayOf(1)), PickBus.TARGET_EDITOR, id)
        assertNull(bus.editorRequestId)
        val values = mutableListOf<IncomingImage?>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { bus.bubblePicks.collect { values += it } }
        bus.cancel(PickBus.TARGET_BUBBLE); yield()
        assertEquals(listOf<IncomingImage?>(null), values)
        collector.cancel()
    }
    @Test fun `clipboard foreground replacement finishes its owner while a collected owner is safe`() = runBlocking<Unit> {
        val bus = ClipboardBus()
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        val old = bus.awaitConsume()
        assertFalse(bus.attachForeground(activity, null))
        assertTrue(bus.attachForeground(activity, bus.consumeRequestId))
        val pending = bus.awaitConsume()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(activity.isFinishing)
        assertNull(old.await())
        ReflectionHelpers.setField(bus, "activity", WeakReference<Activity>(null))
        bus.cancelConsume()
        assertNull(pending.await())
        val id = "already-completed"
        ReflectionHelpers.setField(bus, "consumeRequestId", id)
        assertFalse(bus.attachForeground(activity, id))
        bus.offer(ClipboardRead(ClipboardLook.Empty, true, id))
        assertNull(bus.consumeRequestId)
        controller.pause().stop().destroy()
    }
}
