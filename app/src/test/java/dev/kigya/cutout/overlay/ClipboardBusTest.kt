package dev.kigya.cutout.overlay

import android.app.Activity
import android.app.Application
import android.os.Looper
import dev.kigya.cutout.data.ClipboardLook
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
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ClipboardBusTest {
    @Test fun `bubble clipboard request replacement and matching cancellation finish only current owner`() = runBlocking<Unit> {
        val bus = ClipboardBus()
        val first = Robolectric.buildActivity(Activity::class.java).setup()
        val firstWaiter = bus.awaitBubble()
        val firstId = requireNotNull(bus.bubbleRequestId)
        assertFalse(bus.attachForeground(first.get(), null))
        assertFalse(bus.attachForeground(first.get(), "stale"))
        assertTrue(bus.attachForeground(first.get(), firstId))

        val second = Robolectric.buildActivity(Activity::class.java).setup()
        val secondWaiter = bus.awaitBubble()
        val secondId = requireNotNull(bus.bubbleRequestId)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(first.get().isFinishing)
        assertNull(firstWaiter.await())
        assertFalse(bus.attachForeground(first.get(), firstId))
        bus.offer(ClipboardRead(ClipboardLook.Empty, true, firstId))
        bus.cancelBubble(firstId)
        assertFalse(secondWaiter.isCompleted)
        assertEquals(secondId, bus.bubbleRequestId)

        assertTrue(bus.attachForeground(second.get(), secondId))
        bus.cancelBubble(secondId)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(second.get().isFinishing)
        assertNull(secondWaiter.await())
        assertNull(bus.bubbleRequestId)
        first.pause().stop().destroy(); second.pause().stop().destroy()
    }

    @Test fun `stale and uncorrelated consume reads cannot settle a foreground request`() = runBlocking {
        val bus = ClipboardBus()
        val pending = bus.awaitConsume()
        val id = requireNotNull(bus.consumeRequestId)
        bus.offer(ClipboardRead(ClipboardLook.Empty, true))
        bus.offer(ClipboardRead(ClipboardLook.Empty, true, "stale"))
        bus.cancelConsume("stale")
        assertFalse(pending.isCompleted)
        assertEquals(id, bus.consumeRequestId)
        bus.cancelBubble("stale")
        assertFalse(pending.isCompleted)
        bus.cancelConsume(id)
        assertNull(pending.await())
        assertNull(bus.consumeRequestId)
    }
    @Test fun bubbleRequestsRejectStaleResponsesAndRemainSeparateFromEditor() = runBlocking {
        val bus = ClipboardBus()
        val editor = bus.awaitConsume()
        val editorId = bus.consumeRequestId
        val old = bus.awaitBubble()
        val oldId = bus.bubbleRequestId
        bus.cancelBubble()
        assertNull(old.await())
        val current = bus.awaitBubble()
        val currentId = bus.bubbleRequestId
        bus.offer(ClipboardRead(ClipboardLook.Empty, true, oldId))
        bus.cancelBubble(oldId)
        assertFalse(current.isCompleted)
        assertFalse(editor.isCompleted)
        bus.offer(ClipboardRead(ClipboardLook.NoFocus, true, currentId))
        assertEquals(ClipboardLook.NoFocus, current.await())
        assertEquals(editorId, bus.consumeRequestId)
        bus.offer(ClipboardRead(ClipboardLook.Empty, true, editorId))
        assertEquals(ClipboardLook.Empty, editor.await())
    }
    @Test fun deliveredReadCannotEraseARequestStartedByItsCompletion() = runBlocking {
        val bus = ClipboardBus()
        val first = bus.awaitConsume()
        val id = bus.consumeRequestId
        var next: kotlinx.coroutines.CompletableDeferred<ClipboardLook?>? = null
        first.invokeOnCompletion { next = bus.awaitConsume() }
        bus.offer(ClipboardRead(ClipboardLook.Empty, true, id))
        assertEquals(ClipboardLook.Empty, first.await())
        assertTrue(bus.consumeRequestId != null && bus.consumeRequestId != id)
        bus.cancelConsume()
        assertNull(next!!.await())
    }
    @Test fun cancellationCompletionCannotEraseAReentrantNewClipboardRequest() = runBlocking {
        val bus = ClipboardBus()
        val old = bus.awaitConsume()
        var replacement: kotlinx.coroutines.CompletableDeferred<ClipboardLook?>? = null
        old.invokeOnCompletion { replacement = bus.awaitConsume() }
        bus.cancelConsume()
        val expected = ClipboardLook.Found(IncomingImage.Raster(byteArrayOf(9)))
        assertTrue(bus.consumeRequestId != null)
        bus.offer(ClipboardRead(expected, true, bus.consumeRequestId))
        assertEquals(expected, replacement!!.await())
        assertNull(old.await())
    }

    @Test
    fun lateResultFromCancelledForegroundRequestIsNotEmittedToBubbleFlow() = runBlocking {
        val bus = ClipboardBus()
        val bubbleReads = mutableListOf<ClipboardRead>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.reads.collect { bubbleReads += it }
        }

        val foregroundWaiter = bus.awaitConsume()
        val requestId = requireNotNull(bus.consumeRequestId)
        bus.cancelConsume(requestId)
        bus.offer(ClipboardRead(ClipboardLook.Found(IncomingImage.Raster(byteArrayOf(1))), consume = true, requestId = requestId))
        yield()

        assertNull(foregroundWaiter.await())
        assertTrue("a late foreground read must not become a bubble read", bubbleReads.isEmpty())
        assertNull(bus.consumeRequestId)
        collector.cancel()
    }

    @Test
    fun oldResultAndCancellationCannotCompleteOrClearNewForegroundRequest() = runBlocking {
        val bus = ClipboardBus()
        val bubbleReads = mutableListOf<ClipboardRead>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.reads.collect { bubbleReads += it }
        }

        val oldWaiter = bus.awaitConsume()
        val oldRequestId = requireNotNull(bus.consumeRequestId)
        val newWaiter = bus.awaitConsume()
        val newRequestId = requireNotNull(bus.consumeRequestId)
        val expected = ClipboardLook.Found(IncomingImage.Raster(byteArrayOf(7, 8)))

        assertNull(oldWaiter.await())
        assertTrue(oldRequestId != newRequestId)
        bus.offer(ClipboardRead(expected, consume = true, requestId = oldRequestId))
        bus.cancelConsume(oldRequestId)
        yield()

        assertFalse("a stale request must not settle the current waiter", newWaiter.isCompleted)
        assertEquals(newRequestId, bus.consumeRequestId)
        assertTrue("a stale request must not enter the bubble flow", bubbleReads.isEmpty())

        bus.offer(ClipboardRead(expected, consume = true, requestId = newRequestId))
        assertEquals(expected, newWaiter.await())
        assertNull(bus.consumeRequestId)
        assertTrue("foreground reads are delivered to their waiter, not BubblePresenter", bubbleReads.isEmpty())
        collector.cancel()
    }

    @Test
    fun requestlessBubbleReadStillReachesBubbleFlow() = runBlocking {
        val bus = ClipboardBus()
        val bubbleReads = mutableListOf<ClipboardRead>()
        val collector = launch(start = CoroutineStart.UNDISPATCHED) {
            bus.reads.collect { bubbleReads += it }
        }
        val expected = ClipboardRead(
            ClipboardLook.Found(IncomingImage.Raster(byteArrayOf(3))),
            consume = true,
        )

        bus.offer(expected)
        yield()

        assertEquals(listOf(expected), bubbleReads)
        collector.cancel()
    }
}
