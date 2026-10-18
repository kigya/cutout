package dev.kigya.cutout.overlay

import dev.kigya.cutout.data.ClipboardLook
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class ClipboardRead(
    val look: ClipboardLook,
    val consume: Boolean,
    val requestId: String? = null,
)

@Singleton
class ClipboardBus @Inject constructor() {
    private val readsFlow = MutableSharedFlow<ClipboardRead>(extraBufferCapacity = 4)
    val reads = readsFlow.asSharedFlow()
    @Volatile var consumeRequestId: String? = null
        private set
    private var activity: java.lang.ref.WeakReference<android.app.Activity>? = null
    private val consumeSlot = AtomicReference<CompletableDeferred<ClipboardLook?>?>(null)
    @Volatile var bubbleRequestId: String? = null
        private set
    private var bubbleActivity: java.lang.ref.WeakReference<android.app.Activity>? = null
    private var bubbleSlot: CompletableDeferred<ClipboardLook?>? = null

    @Synchronized fun awaitBubble(): CompletableDeferred<ClipboardLook?> {
        val pending = CompletableDeferred<ClipboardLook?>()
        val previous = bubbleSlot
        val owner = bubbleActivity?.get()
        bubbleSlot = pending
        bubbleRequestId = java.util.UUID.randomUUID().toString()
        bubbleActivity = null
        previous?.complete(null)
        owner?.runOnUiThread { owner.finish() }
        return pending
    }

    @Synchronized fun cancelBubble(requestId: String? = null) {
        if (requestId != null && requestId != bubbleRequestId) return
        val pending = bubbleSlot
        val owner = bubbleActivity?.get()
        bubbleSlot = null
        bubbleRequestId = null
        bubbleActivity = null
        pending?.complete(null)
        owner?.runOnUiThread { owner.finish() }
    }

    @Synchronized fun awaitConsume(): CompletableDeferred<ClipboardLook?> {
        val deferred = CompletableDeferred<ClipboardLook?>()
        val previous = consumeSlot.getAndSet(deferred)
        val previousActivity = activity?.get()
        activity = null
        consumeRequestId = java.util.UUID.randomUUID().toString()
        previous?.complete(null)
        previousActivity?.runOnUiThread { previousActivity.finish() }
        return deferred
    }

    @Synchronized fun attachForeground(value: android.app.Activity, requestId: String?): Boolean {
        if (requestId != null && requestId == bubbleRequestId && bubbleSlot != null) {
            bubbleActivity = java.lang.ref.WeakReference(value)
            return true
        }
        if (requestId == null || requestId != consumeRequestId || consumeSlot.get() == null) return false
        activity = java.lang.ref.WeakReference(value)
        return true
    }

    @Synchronized fun offer(read: ClipboardRead) {
        if (read.requestId != null && read.requestId == bubbleRequestId) {
            val pending = bubbleSlot
            bubbleSlot = null
            bubbleRequestId = null
            bubbleActivity = null
            pending?.complete(read.look)
            return
        }
        if (read.requestId != null) {
            if (read.requestId != consumeRequestId) return
            val pending = consumeSlot.getAndSet(null)
            consumeRequestId = null
            activity = null
            pending?.complete(read.look)
            return
        }
        // Only explicit bubble reads are eligible for the bubble flow.
        readsFlow.tryEmit(read)
    }

    @Synchronized fun cancelConsume(requestId: String? = null) {
        if (requestId != null && requestId != consumeRequestId) return
        val pending = consumeSlot.getAndSet(null)
        val owner = activity?.get()
        consumeRequestId = null
        activity = null
        pending?.complete(null)
        owner?.runOnUiThread { owner.finish() }
    }
}
