package dev.kigya.cutout.overlay

import dev.kigya.cutout.data.IncomingImage
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

@Singleton
class PickBus @Inject constructor() {
    private val bubbleFlow = MutableSharedFlow<IncomingImage?>(extraBufferCapacity = 1)
    val bubblePicks = bubbleFlow.asSharedFlow()
    @Volatile var editorRequestId: String? = null
        private set
    private var editorActivity: java.lang.ref.WeakReference<android.app.Activity>? = null
    private val editorSlot = AtomicReference<CompletableDeferred<IncomingImage?>?>(null)

    @Synchronized fun awaitEditor(): CompletableDeferred<IncomingImage?> {
        val deferred = CompletableDeferred<IncomingImage?>()
        val previous = editorSlot.getAndSet(deferred)
        val previousActivity = editorActivity?.get()
        editorActivity = null
        editorRequestId = java.util.UUID.randomUUID().toString()
        // Publish the replacement before resuming the old caller: completion can reenter this bus.
        previous?.complete(null)
        previousActivity?.runOnUiThread { previousActivity.finish() }
        return deferred
    }

    @Synchronized fun attachEditor(activity: android.app.Activity, requestId: String?): Boolean {
        if (requestId == null || requestId != editorRequestId || editorSlot.get() == null) return false
        editorActivity = java.lang.ref.WeakReference(activity)
        return true
    }

    @Synchronized fun offer(image: IncomingImage, target: String, requestId: String? = null) {
        if (target == TARGET_EDITOR) {
            if (requestId != null && requestId == editorRequestId) {
                val pending = editorSlot.getAndSet(null)
                editorRequestId = null
                pending?.complete(image)
            }
        } else {
            bubbleFlow.tryEmit(image)
        }
    }

    @Synchronized fun cancel(target: String, requestId: String? = null) {
        if (target == TARGET_EDITOR) {
            if (requestId != null && requestId != editorRequestId) return
            val pending = editorSlot.getAndSet(null)
            val owner = editorActivity?.get()
            editorRequestId = null
            editorActivity = null
            pending?.complete(null)
            owner?.runOnUiThread { owner.finish() }
        } else {
            bubbleFlow.tryEmit(null)
        }
    }

    companion object {
        const val TARGET_BUBBLE = "bubble"
        const val TARGET_EDITOR = "editor"
    }
}
