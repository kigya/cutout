package dev.kigya.cutout.overlay

import android.app.Activity
import com.facebook.react.ReactHost
import dev.kigya.cutout.MainActivityState
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Test

class BubbleReactLifecycleTest {
    @Test fun `lifecycle callbacks are inert before attachment and while an Activity is resumed`() {
        val host = mockk<ReactHost>(relaxed = true)
        val state = MainActivityState()
        val lifecycle = BubbleReactLifecycle({ host }, state)

        lifecycle.afterActivityCallback()
        lifecycle.beforeActivityResume()
        lifecycle.detach()
        verify(exactly = 0) { host.onHostResume(any()) }
        verify(exactly = 0) { host.onHostPause() }

        val activity = mockk<Activity>()
        state.started(activity)
        state.resumed(activity)
        lifecycle.attach()
        lifecycle.afterActivityCallback()
        lifecycle.beforeActivityResume()
        lifecycle.detach()

        verify(exactly = 0) { host.onHostResume(any()) }
        verify(exactly = 0) { host.onHostPause() }
        assertTrue(state.state.value.resumed)
    }

    @Test fun `cold service resumes runtime without an Activity and releases it once`() {
        val host = mockk<ReactHost>(relaxed = true)
        val state = MainActivityState()
        val lifecycle = BubbleReactLifecycle({ host }, state)
        lifecycle.attach()
        verify(exactly = 1) { host.onHostResume(null) }
        assertFalse(state.state.value.visible)
        assertFalse(state.state.value.resumed)
        lifecycle.detach()
        lifecycle.detach()
        verify(exactly = 1) { host.onHostPause() }
    }

    @Test fun `Activity handoff updates React context and leaving it restores overlay runtime`() {
        val host = mockk<ReactHost>(relaxed = true)
        val state = MainActivityState()
        val activity = mockk<Activity>()
        val lifecycle = BubbleReactLifecycle({ host }, state)
        state.started(activity)
        state.resumed(activity)
        lifecycle.attach()
        verify(exactly = 0) { host.onHostResume(null) }
        state.paused(activity)
        lifecycle.afterActivityCallback()
        verify(exactly = 1) { host.onHostResume(null) }
        assertTrue(state.state.value.visible)
        assertFalse(state.state.value.resumed)
        lifecycle.beforeActivityResume()
        verify(exactly = 1) { host.onHostPause() }
        state.resumed(activity)
        lifecycle.detach()
        verify(exactly = 1) { host.onHostPause() }
    }

    @Test fun `destroying old Activity cannot mark its replacement invisible`() {
        val state = MainActivityState()
        val old = mockk<Activity>()
        val replacement = mockk<Activity>()
        state.started(old)
        state.started(replacement)
        state.resumed(replacement)
        state.destroyed(old)
        assertTrue(state.state.value.visible)
        assertTrue(state.state.value.resumed)
        state.stopped(replacement)
        assertFalse(state.state.value.visible)
        assertFalse(state.state.value.resumed)
    }
}
