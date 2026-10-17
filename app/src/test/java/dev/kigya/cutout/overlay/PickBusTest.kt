package dev.kigya.cutout.overlay

import dev.kigya.cutout.data.IncomingImage
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

class PickBusTest {
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

        bus.offer(expected, PickBus.TARGET_BUBBLE)
        yield()

        assertEquals(listOf(expected), bubblePicks)
        collector.cancel()
    }
}
