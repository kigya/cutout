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

    fun awaitConsume(): CompletableDeferred<ClipboardLook?> {
        cancelConsume()
        val deferred = CompletableDeferred<ClipboardLook?>()
        consumeRequestId = java.util.UUID.randomUUID().toString()
        consumeSlot.getAndSet(deferred)?.cancel()
        return deferred
    }

    fun attachForeground(value: android.app.Activity, requestId: String?): Boolean {
        if (requestId == null || requestId != consumeRequestId || consumeSlot.get() == null) return false
        activity = java.lang.ref.WeakReference(value)
        return true
    }

    fun offer(read: ClipboardRead) {
        if (read.requestId != null) {
            if (read.requestId != consumeRequestId) return
            consumeSlot.getAndSet(null)?.complete(read.look)
            consumeRequestId = null
            return
        }
        // Only explicit bubble reads are eligible for the bubble flow.
        readsFlow.tryEmit(read)
    }

    fun cancelConsume(requestId: String? = null) {
        if (requestId != null && requestId != consumeRequestId) return
        consumeSlot.getAndSet(null)?.complete(null)
        consumeRequestId = null
        activity?.get()?.let { value -> value.runOnUiThread { value.finish() } }
        activity = null
    }
}
