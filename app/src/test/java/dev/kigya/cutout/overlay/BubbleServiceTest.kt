package dev.kigya.cutout.overlay

import android.app.Application
import android.content.res.Configuration
import android.content.Intent
import android.os.SystemClock
import android.os.Looper
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.facebook.react.ReactHost
import com.facebook.react.interfaces.fabric.ReactSurface
import dagger.hilt.android.internal.managers.ServiceComponentManager
import dev.kigya.cutout.AppScope
import dev.kigya.cutout.EraseApp
import dev.kigya.cutout.data.BubblePreferences
import dev.kigya.cutout.data.BubbleSpot
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BubbleServiceTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val window = BubbleWindow()
    private val preferences = mockk<BubblePreferences>(relaxed = true)
    private val presenter = mockk<BubblePresenter>(relaxed = true)
    private val state = MutableStateFlow(BubbleUiState())
    private lateinit var surface: ReactSurface
    private lateinit var host: ReactHost

    @Before
    fun setUp() {
        ShadowSettings.setCanDrawOverlays(true)
        every { preferences.spot } returns flowOf(null)
        every { presenter.state } returns state

        host = mockk(relaxed = true)
        surface = mockk(relaxed = true)
        every { surface.view } answers { android.widget.FrameLayout(app) }
        every { host.createSurface(any(), "Bubble", null) } returns surface
    }

    @After
    fun tearDown() {
        ShadowSettings.setCanDrawOverlays(false)
    }

    private fun prepare(service: BubbleService, overlayPermission: Boolean = true) {
        ShadowSettings.setCanDrawOverlays(overlayPermission)
        val application = mockk<EraseApp>(relaxed = true)
        every { application.reactHost } returns host
        ReflectionHelpers.setField(service, "mApplication", application)
        ReflectionHelpers.setField(service, "injected", true)
        ReflectionHelpers.setField(service, "componentManager", mockk<ServiceComponentManager>(relaxed = true))
        service.preferences = preferences
        service.window = window
        service.presenter = presenter
        service.appScope = AppScope(Dispatchers.Unconfined)
    }

    private fun params(service: BubbleService) =
        ReflectionHelpers.getField<WindowManager.LayoutParams>(service, "params")

    @Test
    fun `overlay attaches react surface exposes drag resize settle and clears callbacks on destroy`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()

        assertNotNull(window.move)
        assertNotNull(window.resize)
        assertNotNull(window.settle)
        verify(exactly = 1) { host.start() }
        verify(exactly = 1) { surface.start() }
        val root = ReflectionHelpers.getField<View?>(service, "root")
        assertNotNull(root)
        assertTrue(requireNotNull(root).isAttachedToWindow)

        val layout = params(service)
        val originalRight = layout.x + layout.width
        window.resize?.invoke(120f, 96f)
        shadowOfMain().idle()
        val density = service.resources.displayMetrics.density
        assertEquals((120f * density).toInt(), params(service).width)
        assertEquals((96f * density).toInt(), params(service).height)
        assertEquals(originalRight, params(service).x + params(service).width)

        val beforeDrag = params(service).x
        window.move?.invoke(-24f, 10f)
        shadowOfMain().idle()
        assertEquals(beforeDrag - 24, params(service).x)
        assertTrue(window.settle?.invoke() == true)
        shadowOfMain().idle()
        coVerify(exactly = 1) { preferences.setSpot(any(), any()) }

        controller.destroy()
        assertNull(window.move)
        assertNull(window.resize)
        assertNull(window.settle)
        verify(exactly = 1) { surface.stop() }
        assertFalse(requireNotNull(root).isAttachedToWindow)
    }

    @Test
    fun `missing overlay permission stops before host or window attachment`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service, overlayPermission = false)
        controller.create()

        assertNull(window.move)
        assertNull(window.resize)
        assertNull(window.settle)
        verify(exactly = 0) { host.start() }
        verify(exactly = 0) { host.createSurface(any(), any(), any()) }
        controller.destroy()
    }

    @Test
    fun `hide notification action persists disabled state and stops foreground service`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        val result = service.onStartCommand(Intent(BubbleService.ACTION_HIDE), 0, 1)
        shadowOfMain().idle()

        assertEquals(android.app.Service.START_NOT_STICKY, result)
        coVerify(exactly = 1) { preferences.setEnabled(false) }
        assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun `persisted position is restored and configuration changes reclamp the overlay`() {
        every { preferences.spot } returns flowOf(BubbleSpot(30f, 45f))
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()

        assertEquals(30, params(service).x)
        assertEquals(45, params(service).y)
        service.onConfigurationChanged(Configuration())
        shadowOfMain().idle()
        assertTrue(params(service).x >= 8)
        assertTrue(params(service).y >= 8)

        controller.destroy()
    }

    @Test
    fun `starting attached service after overlay permission is revoked stops it`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()

        ShadowSettings.setCanDrawOverlays(false)
        val result = service.onStartCommand(Intent("ordinary-start"), 0, 2)

        assertEquals(android.app.Service.START_NOT_STICKY, result)
        assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf)
        controller.destroy()
    }

    @Test
    fun `react surface attach failure tears down callbacks and stops service`() {
        every { surface.view } returns null
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()

        assertTrue(org.robolectric.Shadows.shadowOf(service).isStoppedBySelf)
        verify(exactly = 1) { host.start() }
        verify(exactly = 0) { surface.start() }
        controller.destroy()
        assertNull(window.move)
        assertNull(window.resize)
        assertNull(window.settle)
    }

    @Test
    fun `drag gestures outside close target move overlay and settle to persisted edge`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val originX = params(service).x
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 12f, 12f, 0)
        val move = MotionEvent.obtain(downTime, downTime + 100, MotionEvent.ACTION_MOVE, -12f, 30f, 0)
        val moveAgain = MotionEvent.obtain(downTime, downTime + 150, MotionEvent.ACTION_MOVE, -24f, 38f, 0)
        val up = MotionEvent.obtain(downTime, downTime + 200, MotionEvent.ACTION_UP, -24f, 38f, 0)
        try {
            assertFalse(root.onInterceptTouchEvent(down))
            assertTrue(root.onInterceptTouchEvent(move))
            assertTrue(root.onTouchEvent(move))
            shadowOfMain().idle()
            assertTrue(params(service).x < originX)
            val afterFirstMove = params(service).x
            assertTrue(root.onInterceptTouchEvent(moveAgain))
            assertTrue(root.onTouchEvent(moveAgain))
            shadowOfMain().idle()
            assertTrue(params(service).x < afterFirstMove)
            assertTrue(root.onTouchEvent(up))
            shadowOfMain().idle()
            coVerify(exactly = 1) { preferences.setSpot(any(), any()) }
        } finally {
            down.recycle()
            move.recycle()
            moveAgain.recycle()
            up.recycle()
        }
        controller.destroy()
    }

    @Test
    fun `close control region is excluded from drag interception`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 70f, 12f, 0)
        val move = MotionEvent.obtain(downTime, downTime + 100, MotionEvent.ACTION_MOVE, 90f, 28f, 0)
        try {
            assertFalse(root.onInterceptTouchEvent(down))
            assertFalse(root.onInterceptTouchEvent(move))
        } finally {
            down.recycle()
            move.recycle()
        }
        controller.destroy()
    }

    @Test
    fun `only the chip interior outside the close target starts a drag`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val density = service.resources.displayMetrics.density
        val chip = (88f * density).toInt()
        val close = (48f * density).toInt()
        val left = root.width - chip
        val time = SystemClock.uptimeMillis()

        val excludedDowns = listOf(
            left - 1f to chip / 2f,
            (left + chip / 2f) to (chip + 1f),
            (left + chip - close / 2f) to (close / 2f),
        )
        excludedDowns.forEachIndexed { index, (x, y) ->
            val down = MotionEvent.obtain(time + index * 100L, time + index * 100L, MotionEvent.ACTION_DOWN, x, y, 0)
            val move = MotionEvent.obtain(time + index * 100L, time + index * 100L + 20, MotionEvent.ACTION_MOVE, x + 20f, y + 20f, 0)
            try {
                assertFalse(root.onInterceptTouchEvent(down))
                assertFalse(root.onInterceptTouchEvent(move))
            } finally {
                down.recycle()
                move.recycle()
            }
        }

        val validX = left + chip - close - 1f
        val validY = close + 1f
        val down = MotionEvent.obtain(time + 500, time + 500, MotionEvent.ACTION_DOWN, validX, validY, 0)
        val move = MotionEvent.obtain(time + 500, time + 520, MotionEvent.ACTION_MOVE, validX + 20f, validY + 20f, 0)
        val up = MotionEvent.obtain(time + 500, time + 540, MotionEvent.ACTION_UP, validX + 20f, validY + 20f, 0)
        try {
            assertFalse(root.onInterceptTouchEvent(down))
            assertTrue(root.onInterceptTouchEvent(move))
            assertTrue(root.onTouchEvent(move))
            assertTrue(root.onTouchEvent(up))
        } finally {
            down.recycle()
            move.recycle()
            up.recycle()
        }
        controller.destroy()
    }

    @Test
    fun `unexpanded service follows presenter expand and collapse state`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val density = service.resources.displayMetrics.density
        val bubble = (88f * density).toInt()

        state.value = state.value.copy(expanded = true)
        shadowOfMain().idle()
        assertTrue(params(service).width > bubble)
        assertEquals((260f * density).toInt(), params(service).height)

        state.value = state.value.copy(expanded = false)
        shadowOfMain().idle()
        assertEquals(bubble, params(service).width)
        assertEquals(bubble, params(service).height)
        controller.destroy()
    }

    @Test
    fun `left anchored bubble preserves its left edge while resizing and settles on left`() {
        state.value = BubbleUiState(anchorEnd = false)
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()

        window.move?.invoke(-5000f, 0f)
        shadowOfMain().idle()
        val left = params(service).x
        window.resize?.invoke(120f, 96f)
        shadowOfMain().idle()
        assertEquals(left, params(service).x)

        assertTrue(window.settle?.invoke() == false)
        shadowOfMain().idle()
        verify { presenter.setAnchorEnd(false) }
        assertTrue(params(service).x >= 8)
        coVerify(exactly = 1) { preferences.setSpot(any(), any()) }
        controller.destroy()
    }

    @Test
    fun `left anchored chip interior intercepts drag and persists the new position`() {
        state.value = BubbleUiState(anchorEnd = false)
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        // BubbleService starts at the right edge regardless of its remembered anchor;
        // place it on the left before exercising the left-side drag target.
        window.move?.invoke(-5000f, 0f)
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val density = service.resources.displayMetrics.density
        val initialX = params(service).x
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 12f * density, 12f * density, 0)
        val move = MotionEvent.obtain(downTime, downTime + 100, MotionEvent.ACTION_MOVE, 32f * density, 32f * density, 0)
        val up = MotionEvent.obtain(downTime, downTime + 150, MotionEvent.ACTION_UP, 32f * density, 32f * density, 0)
        try {
            assertFalse(root.onInterceptTouchEvent(down))
            assertTrue(root.onInterceptTouchEvent(move))
            assertTrue(root.onTouchEvent(move))
            shadowOfMain().idle()
            assertTrue("left anchored drag should move from the chip's left-side target", params(service).x > initialX)
            assertTrue(root.onTouchEvent(up))
            shadowOfMain().idle()
            verify { presenter.setAnchorEnd(false) }
            coVerify(exactly = 1) { preferences.setSpot(any(), any()) }
        } finally {
            down.recycle()
            move.recycle()
            up.recycle()
        }
        controller.destroy()
    }

    @Test
    fun `left anchored close and outside-chip zones do not start a drag`() {
        state.value = BubbleUiState(anchorEnd = false)
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        window.move?.invoke(-5000f, 0f)
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val density = service.resources.displayMetrics.density
        val chip = 88f * density
        val close = 48f * density
        val now = SystemClock.uptimeMillis()
        val excluded = listOf((chip - close / 2f) to (close / 2f), (chip / 2f) to (chip + 1f))

        excluded.forEachIndexed { index, (x, y) ->
            val time = now + index * 100L
            val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, x, y, 0)
            val move = MotionEvent.obtain(time, time + 20L, MotionEvent.ACTION_MOVE, x + 20f, y + 20f, 0)
            try {
                assertFalse(root.onInterceptTouchEvent(down))
                assertFalse(root.onInterceptTouchEvent(move))
            } finally {
                down.recycle()
                move.recycle()
            }
        }
        controller.destroy()
    }

    @Test
    fun `outside touch is consumed without moving or settling the overlay`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val before = params(service).let { it.x to it.y }
        val now = SystemClock.uptimeMillis()
        val outside = MotionEvent.obtain(now, now, MotionEvent.ACTION_OUTSIDE, -1f, -1f, 0)
        try {
            assertTrue(root.dispatchTouchEvent(outside))
            shadowOfMain().idle()
            assertEquals(before, params(service).let { it.x to it.y })
            coVerify(exactly = 0) { preferences.setSpot(any(), any()) }
        } finally {
            outside.recycle()
            controller.destroy()
        }
    }

    @Test
    fun `cancelled small movement does not turn a tap into an overlay drag`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val downTime = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(downTime, downTime, MotionEvent.ACTION_DOWN, 12f, 12f, 0)
        val smallMove = MotionEvent.obtain(downTime, downTime + 50, MotionEvent.ACTION_MOVE, 15f, 14f, 0)
        val cancel = MotionEvent.obtain(downTime, downTime + 75, MotionEvent.ACTION_CANCEL, 15f, 14f, 0)
        val laterMove = MotionEvent.obtain(downTime, downTime + 100, MotionEvent.ACTION_MOVE, 40f, 35f, 0)
        val outside = MotionEvent.obtain(downTime, downTime + 125, MotionEvent.ACTION_OUTSIDE, 40f, 35f, 0)
        try {
            assertFalse(root.onInterceptTouchEvent(down))
            assertFalse(root.onInterceptTouchEvent(smallMove))
            assertFalse(root.onTouchEvent(smallMove))
            assertFalse(root.onInterceptTouchEvent(cancel))
            assertFalse(root.onInterceptTouchEvent(laterMove))
            assertTrue(root.dispatchTouchEvent(outside))
        } finally {
            down.recycle()
            smallMove.recycle()
            cancel.recycle()
            laterMove.recycle()
            outside.recycle()
        }
        controller.destroy()
    }

    @Test
    fun `cancel after an intercepted drag applies the last delta and settles exactly once`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 12f, 12f, 0)
        val move = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_MOVE, 40f, 35f, 0)
        val cancel = MotionEvent.obtain(time, time + 75, MotionEvent.ACTION_CANCEL, 48f, 39f, 0)
        val afterCancel = MotionEvent.obtain(time, time + 100, MotionEvent.ACTION_MOVE, 60f, 50f, 0)
        val before = params(service).let { it.x to it.y }
        try {
            assertFalse(root.onInterceptTouchEvent(down))
            assertTrue(root.onInterceptTouchEvent(move))
            assertTrue(root.onTouchEvent(move))
            assertTrue(root.onTouchEvent(cancel))
            shadowOfMain().idle()

            val settled = params(service).let { it.x to it.y }
            assertTrue(settled != before)
            assertFalse(root.onTouchEvent(afterCancel))
            assertEquals(settled, params(service).let { it.x to it.y })
            coVerify(exactly = 1) { preferences.setSpot(any(), any()) }
        } finally {
            down.recycle()
            move.recycle()
            cancel.recycle()
            afterCancel.recycle()
            controller.destroy()
        }
    }

    @Test
    fun `start without an intent remains sticky and pre-create configuration is ignored`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)

        service.onConfigurationChanged(Configuration())
        controller.create()
        assertEquals(android.app.Service.START_STICKY, service.onStartCommand(null, 0, 1))
        shadowOfMain().idle()
        controller.destroy()
    }

    @Test
    fun `window callbacks posted from worker thread update on main looper`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val originalWidth = params(service).width
        val callback = window.resize ?: error("resize callback not installed")
        val worker = Thread { callback(120f, 96f) }
        worker.start()
        worker.join()
        shadowOfMain().idle()

        assertTrue(params(service).width > originalWidth)
        controller.destroy()
    }

    @Test
    @Config(sdk = [28])
    fun `pre Android 14 service uses legacy display bounds for its overlay`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()

        assertNotNull(window.move)
        assertTrue(params(service).x >= 8)
        assertTrue(params(service).y >= 8)
        window.resize?.invoke(120f, 96f)
        shadowOfMain().idle()
        assertTrue(params(service).width >= (120f * service.resources.displayMetrics.density).toInt())
        service.onConfigurationChanged(Configuration())
        shadowOfMain().idle()
        controller.destroy()
        assertNull(window.move)
    }

    private fun shadowOfMain() = org.robolectric.Shadows.shadowOf(Looper.getMainLooper())
}
