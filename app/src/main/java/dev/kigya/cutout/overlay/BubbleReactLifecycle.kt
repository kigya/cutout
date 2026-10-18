package dev.kigya.cutout.overlay

import com.facebook.react.ReactHost
import dev.kigya.cutout.MainActivityState

/** Called on the main thread, after ReactActivity's own lifecycle callbacks. */
class BubbleReactLifecycle(
    private val host: () -> ReactHost,
    private val mainActivity: MainActivityState,
) {
    private var attached = false

    fun attach() {
        attached = true
        afterActivityCallback()
    }

    fun afterActivityCallback() {
        if (attached && !mainActivity.state.value.resumed) {
            // Clear the old Activity reference while keeping RN frame/touch dispatch alive.
            host().onHostResume(null)
        }
    }

    fun beforeActivityResume() {
        if (attached && !mainActivity.state.value.resumed) {
            // Force the next ReactActivity resume to update ReactContext.currentActivity.
            // ReactLifecycleStateManager skips onHostResume when already RESUMED.
            host().onHostPause()
        }
    }

    fun detach() {
        if (!attached) return
        attached = false
        if (!mainActivity.state.value.resumed) host().onHostPause()
    }
}
