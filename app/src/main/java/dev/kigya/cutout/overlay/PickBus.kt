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

    fun awaitEditor(): CompletableDeferred<IncomingImage?> {
        cancel(TARGET_EDITOR)
        val deferred = CompletableDeferred<IncomingImage?>()
        editorRequestId = java.util.UUID.randomUUID().toString()
        editorSlot.getAndSet(deferred)?.cancel()
        return deferred
    }

    fun attachEditor(activity: android.app.Activity, requestId: String?): Boolean {
        if (requestId == null || requestId != editorRequestId || editorSlot.get() == null) return false
        editorActivity = java.lang.ref.WeakReference(activity)
        return true
    }

    fun offer(image: IncomingImage, target: String, requestId: String? = null) {
        if (target == TARGET_EDITOR) {
            if (requestId != null && requestId == editorRequestId) {
                editorSlot.getAndSet(null)?.complete(image)
                editorRequestId = null
            }
        } else {
            bubbleFlow.tryEmit(image)
        }
    }

    fun cancel(target: String, requestId: String? = null) {
        if (target == TARGET_EDITOR) {
            if (requestId != null && requestId != editorRequestId) return
            editorSlot.getAndSet(null)?.complete(null)
            editorRequestId = null
            editorActivity?.get()?.let { activity -> activity.runOnUiThread { activity.finish() } }
            editorActivity = null
        } else {
            bubbleFlow.tryEmit(null)
        }
    }

    companion object {
        const val TARGET_BUBBLE = "bubble"
        const val TARGET_EDITOR = "editor"
    }
}
