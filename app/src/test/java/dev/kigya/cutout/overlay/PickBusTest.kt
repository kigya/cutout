package dev.kigya.cutout.overlay

import dev.kigya.cutout.data.IncomingImage
import android.app.Activity
import android.app.Application
import android.os.Looper
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.junit.runner.RunWith
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PickBusTest {
    @Test fun `bubble attachment replacement and matching cancellation finish only its owner`() = runBlocking<Unit> {
        val bus = PickBus()
        val values = mutableListOf<IncomingImage?>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) { bus.bubblePicks.collect { values += it } }
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        val firstId = bus.beginBubble()
        assertFalse(bus.attachBubble(first.get(), null))
        assertFalse(bus.attachBubble(first.get(), "stale"))
        assertTrue(bus.attachBubble(first.get(), firstId))

        val secondId = bus.beginBubble()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(first.get().isFinishing)
        assertFalse(bus.attachBubble(first.get(), firstId))
        bus.cancel(PickBus.TARGET_BUBBLE, firstId)
        assertEquals(secondId, bus.bubbleRequestId)
        assertTrue(values.isEmpty())

        val second = Robolectric.buildActivity(Activity::class.java).setup()
        assertTrue(bus.attachBubble(second.get(), secondId))
        bus.cancel(PickBus.TARGET_BUBBLE, secondId)
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(second.get().isFinishing)
        yield()
        assertNull(bus.bubbleRequestId)
        assertEquals(listOf<IncomingImage?>(null), values)
        collector.cancel()
        first.pause().stop().destroy(); second.pause().stop().destroy()
    }

    @Test fun `unmatched editor offers and target cancellation leave the current waiter pending`() = runBlocking {
        val bus = PickBus()
        val pending = bus.awaitEditor()
        val id = requireNotNull(bus.editorRequestId)
        bus.offer(IncomingImage.Raster(byteArrayOf(1)), PickBus.TARGET_EDITOR)
        bus.offer(IncomingImage.Raster(byteArrayOf(2)), PickBus.TARGET_EDITOR, "stale")
        bus.cancel(PickBus.TARGET_EDITOR, "stale")
        assertFalse(pending.isCompleted)
        assertEquals(id, bus.editorRequestId)
        bus.cancel(PickBus.TARGET_BUBBLE)
        assertFalse(pending.isCompleted)
        bus.cancel(PickBus.TARGET_EDITOR, id)
        assertNull(pending.await())
        assertNull(bus.editorRequestId)
    }
    @Test fun deliveredResultCannotEraseARequestStartedByItsCompletion() = runBlocking {
        val bus = PickBus()
        val first = bus.awaitEditor()
        val id = bus.editorRequestId
        var next: kotlinx.coroutines.CompletableDeferred<IncomingImage?>? = null
        first.invokeOnCompletion { next = bus.awaitEditor() }
        val image = IncomingImage.Raster(byteArrayOf(3))
        bus.offer(image, PickBus.TARGET_EDITOR, id)
        assertEquals(image, first.await())
        assertTrue(bus.editorRequestId != null && bus.editorRequestId != id)
        bus.cancel(PickBus.TARGET_EDITOR)
        assertNull(next!!.await())
    }
    @Test fun cancellationCompletionCannotEraseAReentrantNewPickerRequest() = runBlocking {
        val bus = PickBus()
        val old = bus.awaitEditor()
        var replacement: kotlinx.coroutines.CompletableDeferred<IncomingImage?>? = null
        old.invokeOnCompletion { replacement = bus.awaitEditor() }
        bus.cancel(PickBus.TARGET_EDITOR)
        val expected = IncomingImage.Raster(byteArrayOf(9))
        assertTrue(bus.editorRequestId != null)
        bus.offer(expected, PickBus.TARGET_EDITOR, bus.editorRequestId)
        assertEquals(expected, replacement!!.await())
        assertNull(old.await())
    }

    @Test
    fun lateCancelledEditorPickDoesNotEnterBubbleFlow() = runBlocking {
        val bus = PickBus()
        val bubblePicks = mutableListOf<IncomingImage?>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.bubblePicks.collect { bubblePicks += it }
        }

        val editorWaiter = bus.awaitEditor()
        val requestId = requireNotNull(bus.editorRequestId)
        bus.cancel(PickBus.TARGET_EDITOR, requestId)
        bus.offer(IncomingImage.Raster(byteArrayOf(1, 2)), PickBus.TARGET_EDITOR, requestId)
        yield()

        assertNull(editorWaiter.await())
        assertTrue("a late editor result must not be sent to the bubble collector", bubblePicks.isEmpty())
        assertNull(bus.editorRequestId)
        collector.cancel()
    }

    @Test
    fun oldEditorResultAndCancellationCannotCompleteOrClearNewRequest() = runBlocking {
        val bus = PickBus()
        val bubblePicks = mutableListOf<IncomingImage?>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.bubblePicks.collect { bubblePicks += it }
        }

        val oldWaiter = bus.awaitEditor()
        val oldRequestId = requireNotNull(bus.editorRequestId)
        val newWaiter = bus.awaitEditor()
        val newRequestId = requireNotNull(bus.editorRequestId)
        val expected = IncomingImage.Raster(byteArrayOf(7, 8))

        assertNull(oldWaiter.await())
        assertTrue(oldRequestId != newRequestId)
        bus.offer(IncomingImage.Raster(byteArrayOf(0)), PickBus.TARGET_EDITOR, oldRequestId)
        bus.cancel(PickBus.TARGET_EDITOR, oldRequestId)
        yield()

        assertFalse("an old picker result must not settle the current waiter", newWaiter.isCompleted)
        assertEquals(newRequestId, bus.editorRequestId)
        assertTrue("a stale editor result must not enter the bubble flow", bubblePicks.isEmpty())

        bus.offer(expected, PickBus.TARGET_EDITOR, newRequestId)
        assertEquals(expected, newWaiter.await())
        assertNull(bus.editorRequestId)
        assertTrue(bubblePicks.isEmpty())
        collector.cancel()
    }

    @Test
    fun bubblePickStillReachesBubbleFlow() = runBlocking {
        val bus = PickBus()
        val bubblePicks = mutableListOf<IncomingImage?>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.bubblePicks.collect { bubblePicks += it }
        }
        val expected = IncomingImage.Raster(byteArrayOf(4, 5, 6))

        bus.offer(expected, PickBus.TARGET_BUBBLE, bus.beginBubble())
        yield()

        assertEquals(listOf(expected), bubblePicks)
        collector.cancel()
    }
}
