package dev.kigya.cutout.overlay

import android.app.Application
import android.graphics.Insets
import android.graphics.Rect
import android.content.res.Configuration
import android.content.Intent
import android.os.SystemClock
import android.os.Looper
import android.view.MotionEvent
import android.view.WindowInsets
import android.view.WindowMetrics
import android.view.InputDevice
import android.view.Gravity
import android.view.ViewConfiguration
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import com.facebook.react.ReactHost
import com.facebook.react.interfaces.fabric.ReactSurface
import dagger.hilt.android.internal.managers.ServiceComponentManager
import dev.kigya.cutout.AppScope
import dev.kigya.cutout.EraseApp
import dev.kigya.cutout.data.BubbleAppearance
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
import org.junit.Assert.assertSame
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
import java.time.Duration

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
        every { preferences.appearance } returns kotlinx.coroutines.flow.MutableStateFlow(BubbleAppearance())
        every { preferences.onboardingCompleted } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        every { preferences.enabled } returns flowOf(true)
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

    @Test
    fun `sticky restart with disabled preference never creates a React surface`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        every { preferences.appearance } returns kotlinx.coroutines.flow.MutableStateFlow(BubbleAppearance())
        every { preferences.onboardingCompleted } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        every { preferences.enabled } returns flowOf(false)
        controller.create()
        shadowOfMain().idle()
        verify(exactly = 0) { host.createSurface(any(), any(), any()) }
        assertNull(window.geometry.value)
        controller.destroy()
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

    private fun pointers(
        downTime: Long,
        eventTime: Long,
        action: Int,
        ids: IntArray,
        xs: FloatArray,
        ys: FloatArray,
    ): MotionEvent {
        val properties = Array(ids.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = ids[index]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(ids.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = xs[index]
                y = ys[index]
                pressure = 1f
                size = 1f
            }
        }
        return MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            ids.size,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0,
        )
    }

    @Test
    @Config(qualifiers = "ar-rSA-ldrtl")
    fun `RTL overlay keeps the persistent surface physically left aligned`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        assertEquals(View.LAYOUT_DIRECTION_LTR, root.layoutDirection)
        assertEquals(0, root.getChildAt(0).left)
        controller.destroy()
    }

    @Test
    fun `overlay attaches fixed react surface and native state owns viewport size`() {
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
        val rootGroup = requireNotNull(root) as ViewGroup
        assertEquals(1, rootGroup.childCount)
        assertEquals(
            (640f * service.resources.displayMetrics.density).toInt(),
            rootGroup.getChildAt(0).measuredWidth,
        )

        val layout = params(service)
        assertEquals(Gravity.LEFT, layout.gravity and Gravity.HORIZONTAL_GRAVITY_MASK)
        assertTrue(layout.flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
        assertTrue(layout.flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
        assertTrue(layout.flags and WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED != 0)
        assertTrue(layout.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE != 0)
        assertTrue(layout.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL != 0)
        assertEquals(0, layout.fitInsetsTypes)
        assertFalse(layout.canPlayMoveAnimation())
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS,
            layout.layoutInDisplayCutoutMode,
        )
        val originalWidth = layout.width
        val originalHeight = layout.height
        window.resize?.invoke(120f, 96f)
        shadowOfMain().idle()
        assertEquals(originalWidth, params(service).width)
        assertEquals(originalHeight, params(service).height)
        assertEquals(72, window.geometry.value?.windowWidthDp)
        assertEquals(72, window.geometry.value?.windowHeightDp)
        assertEquals(640, window.geometry.value?.surfaceWidthDp)
        assertEquals(400, window.geometry.value?.surfaceHeightDp)
        assertEquals(272, window.geometry.value?.chipLeftDp)
        assertEquals(260, window.geometry.value?.panelHeightDp)

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
        assertTrue(params(service).x >= 12)
        assertTrue(params(service).y >= 8)

        controller.destroy()
    }

    @Test
    fun `persisted position near the right edge restores right anchoring`() {
        val width = app.resources.displayMetrics.widthPixels
        every { preferences.spot } returns flowOf(BubbleSpot((width - 30).toFloat(), 45f))
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()

        assertTrue(state.value.anchorEnd)
        verify { presenter.setAnchorEnd(true) }
        assertTrue(params(service).x > app.resources.displayMetrics.widthPixels / 2)

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
    fun `former close region inside the chip participates in drag interception`() {
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
            assertTrue(root.onInterceptTouchEvent(move))
            assertTrue(root.onTouchEvent(move))
        } finally {
            down.recycle()
            move.recycle()
        }
        controller.destroy()
    }

    @Test
    fun `every point inside the chip including the former close corner starts a drag`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val density = service.resources.displayMetrics.density
        val chip = (72f * density).toInt()
        val left = 0
        val time = SystemClock.uptimeMillis()
        val validX = left + chip - 2f
        val validY = 2f
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, validX, validY, 0)
        val move = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_MOVE, validX - 20f, validY + 20f, 0)
        val up = MotionEvent.obtain(time, time + 40, MotionEvent.ACTION_UP, validX - 20f, validY + 20f, 0)
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
    fun `dispatch drag from chip padding works when the child has no touch target`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val density = service.resources.displayMetrics.density
        val startX = 68f * density
        val startY = 68f * density
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, startX, startY, 0)
        val move = MotionEvent.obtain(time, time + 50, MotionEvent.ACTION_MOVE, startX - 50f, startY, 0)
        val up = MotionEvent.obtain(time, time + 80, MotionEvent.ACTION_UP, startX - 50f, startY, 0)
        val initialX = params(service).x
        try {
            assertTrue(root.dispatchTouchEvent(down))
            assertTrue(root.dispatchTouchEvent(move))
            shadowOfMain().idle()
            assertTrue(params(service).x < initialX)
            assertTrue(root.dispatchTouchEvent(up))
            shadowOfMain().idle()
            coVerify(exactly = 1) { preferences.setSpot(any(), any()) }
        } finally {
            down.recycle()
            move.recycle()
            up.recycle()
            controller.destroy()
        }
    }

    @Test
    fun `unexpanded service follows presenter expand and collapse state`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val density = service.resources.displayMetrics.density
        val bubble = (72f * density).toInt()

        state.value = state.value.copy(expanded = true)
        shadowOfMain().idle()
        assertTrue(params(service).width > bubble)
        assertEquals((260f * density).toInt(), params(service).height)
        assertEquals(260, window.geometry.value?.panelHeightDp)

        state.value = state.value.copy(expanded = false)
        shadowOfMain().idle()
        assertEquals(bubble, params(service).width)
        assertEquals(bubble, params(service).height)
        assertEquals(260, window.geometry.value?.panelHeightDp)
        controller.destroy()
    }

    @Test
    fun `phase geometry tracks viewport height while panel metadata persists when collapsed`() {
        state.value = BubbleUiState(expanded = true, phase = BubblePhase.Working)
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val density = service.resources.displayMetrics.density

        assertEquals((requireNotNull(window.geometry.value).windowWidthDp * density).toInt(), params(service).width)
        assertEquals((340f * density).toInt(), params(service).height)
        assertEquals(340, window.geometry.value?.windowHeightDp)
        assertEquals(340, window.geometry.value?.panelHeightDp)
        val workingGeometryRevision = requireNotNull(window.geometry.value).revision

        state.value = state.value.copy(percent = 50)
        shadowOfMain().idle()
        assertEquals(workingGeometryRevision, window.geometry.value?.revision)

        state.value = state.value.copy(phase = BubblePhase.Copied)
        shadowOfMain().idle()
        assertEquals((400f * density).toInt(), params(service).height)
        assertEquals(400, window.geometry.value?.windowHeightDp)
        assertEquals(400, window.geometry.value?.panelHeightDp)

        state.value = state.value.copy(expanded = false)
        shadowOfMain().idle()
        assertEquals((72f * density).toInt(), params(service).width)
        assertEquals((72f * density).toInt(), params(service).height)
        assertEquals(72, window.geometry.value?.windowHeightDp)
        assertEquals(400, window.geometry.value?.panelHeightDp)

        state.value = state.value.copy(expanded = true, phase = BubblePhase.Failed, editReady = false)
        shadowOfMain().idle()
        assertEquals((260f * density).toInt(), params(service).height)
        state.value = state.value.copy(editReady = true)
        shadowOfMain().idle()
        assertEquals((400f * density).toInt(), params(service).height)

        controller.destroy()
    }

    @Test
    fun `collapse and expand preserve chip coordinates and geometry revisions are monotonic`() {
        val firstController = Robolectric.buildService(BubbleService::class.java)
        val firstService = firstController.get()
        prepare(firstService)
        firstController.create()
        shadowOfMain().idle()
        val collapsedChipX = params(firstService).x
        val firstRevision = requireNotNull(window.geometry.value).revision

        state.value = state.value.copy(expanded = true)
        shadowOfMain().idle()
        val visibleChipOffset = requireNotNull(window.geometry.value).windowWidthDp - 72
        val expandedChipX = params(firstService).x + (visibleChipOffset * firstService.resources.displayMetrics.density).toInt()
        assertEquals(collapsedChipX, expandedChipX)
        state.value = state.value.copy(expanded = false)
        shadowOfMain().idle()
        assertEquals(collapsedChipX, params(firstService).x)

        val activeRevision = requireNotNull(window.geometry.value).revision
        assertTrue(activeRevision > firstRevision)
        window.acknowledgeGeometry?.invoke(activeRevision - 1)
        assertEquals(-1L, ReflectionHelpers.getField(firstService, "acknowledgedGeometryRevision"))
        window.acknowledgeGeometry?.invoke(activeRevision)
        assertEquals(activeRevision, ReflectionHelpers.getField(firstService, "acknowledgedGeometryRevision"))
        window.acknowledgeGeometry?.invoke(firstRevision)
        assertEquals(activeRevision, ReflectionHelpers.getField(firstService, "acknowledgedGeometryRevision"))

        firstController.destroy()
        val secondController = Robolectric.buildService(BubbleService::class.java)
        val secondService = secondController.get()
        prepare(secondService)
        secondController.create()
        shadowOfMain().idle()
        assertTrue(requireNotNull(window.geometry.value).revision > activeRevision)
        secondController.destroy()
    }

    @Test
    fun `fractional drag deltas accumulate and do not emit geometry every frame`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val initialX = params(service).x
        val geometryRevision = requireNotNull(window.geometry.value).revision

        repeat(10) { window.move?.invoke(-0.4f, 0f) }
        shadowOfMain().idle()

        assertEquals(initialX - 4, params(service).x)
        assertEquals(geometryRevision, window.geometry.value?.revision)
        controller.destroy()
    }

    @Test
    fun `drag starts only after system touch slop`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val slop = ViewConfiguration.get(service).scaledTouchSlop
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 24f, 24f, 0)
        val belowSlop = MotionEvent.obtain(time, time + 10, MotionEvent.ACTION_MOVE, 24f + slop - 1, 24f, 0)
        val beyondSlop = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_MOVE, 24f + slop + 1, 24f, 0)
        try {
            assertFalse(root.onInterceptTouchEvent(down))
            assertFalse(root.onInterceptTouchEvent(belowSlop))
            assertTrue(root.onInterceptTouchEvent(beyondSlop))
            assertTrue(root.onTouchEvent(beyondSlop))
        } finally {
            down.recycle()
            belowSlop.recycle()
            beyondSlop.recycle()
            controller.destroy()
        }
    }

    @Test
    fun `chip drag can intercept even when react native child disallows parent interception`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 24f, 24f, 0)
        val move = MotionEvent.obtain(time, time + 20, MotionEvent.ACTION_MOVE, 48f, 24f, 0)
        try {
            assertFalse(root.onInterceptTouchEvent(down))
            root.requestDisallowInterceptTouchEvent(true)
            assertTrue(root.onInterceptTouchEvent(move))
        } finally {
            down.recycle()
            move.recycle()
            controller.destroy()
        }
    }

    @Test
    @Config(sdk = [28])
    fun `drag keeps tracking the active pointer after its index changes`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val time = SystemClock.uptimeMillis()
        val down = pointers(time, time, MotionEvent.ACTION_DOWN, intArrayOf(5), floatArrayOf(24f), floatArrayOf(24f))
        val pointerDown = pointers(
            time, time + 10,
            MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            intArrayOf(5, 9), floatArrayOf(24f, 60f), floatArrayOf(24f, 24f),
        )
        val pointerUp = pointers(
            time, time + 20,
            MotionEvent.ACTION_POINTER_UP,
            intArrayOf(5, 9), floatArrayOf(24f, 60f), floatArrayOf(24f, 24f),
        )
        val move = pointers(time, time + 40, MotionEvent.ACTION_MOVE, intArrayOf(9), floatArrayOf(30f), floatArrayOf(24f))
        val up = pointers(time, time + 60, MotionEvent.ACTION_UP, intArrayOf(9), floatArrayOf(30f), floatArrayOf(24f))
        val initialX = params(service).x
        try {
            assertFalse(root.onInterceptTouchEvent(down))
            assertTrue(root.onTouchEvent(down))
            assertFalse(root.onInterceptTouchEvent(pointerDown))
            assertTrue(root.onInterceptTouchEvent(pointerUp).not())
            assertTrue(root.onInterceptTouchEvent(move))
            assertTrue(root.onTouchEvent(move))
            assertTrue(params(service).x < initialX)
            assertTrue(root.onInterceptTouchEvent(up))
            assertTrue(root.onTouchEvent(up))
            shadowOfMain().idle()
            coVerify(exactly = 1) { preferences.setSpot(any(), any()) }
        } finally {
            down.recycle()
            pointerDown.recycle()
            pointerUp.recycle()
            move.recycle()
            up.recycle()
            controller.destroy()
        }
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
        assertTrue(params(service).x >= 12)
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
        // The remembered left anchor places the chip at the viewport's left edge.
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
    fun `left anchored outside-chip zones do not start a drag`() {
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
        val chip = 72f * density
        val now = SystemClock.uptimeMillis()
        val excluded = listOf((chip + 1f) to (chip / 2f), (chip / 2f) to (chip + 1f))

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
    fun `expanded chip can cross the screen and settles with the full panel inside safe bounds`() {
        state.value = BubbleUiState(expanded = true, anchorEnd = true)
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()

        val displayWidth = service.resources.displayMetrics.widthPixels
        val density = service.resources.displayMetrics.density
        val chipMinX = (12f * density).toInt()
        val rightAnchorChipX = params(service).x + (window.geometry.value!!.windowWidthDp - 72).let { (it * density).toInt() }
        assertTrue(rightAnchorChipX > displayWidth / 2)

        repeat(8) { window.move?.invoke(-displayWidth / 8f, 0f) }
        assertTrue(ReflectionHelpers.getField<Boolean>(service, "layoutFramePosted"))
        assertTrue(window.settle?.invoke() == false)
        shadowOfMain().idleFor(Duration.ofMillis(250))

        val geometry = requireNotNull(window.geometry.value)
        assertFalse(geometry.anchorEnd)
        assertEquals(chipMinX, params(service).x)
        assertTrue(params(service).x >= chipMinX)
        assertTrue(params(service).x + params(service).width <= displayWidth - chipMinX)
        controller.destroy()
    }

    @Test
    fun `new in-bounds geometry cancels a clamp queued behind the viewport frame`() {
        state.value = BubbleUiState(expanded = false, anchorEnd = true)
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()

        state.value = BubbleUiState(expanded = true, anchorEnd = false)
        shadowOfMain().idleFor(Duration.ofMillis(16))
        assertNotNull(ReflectionHelpers.getField<Long?>(service, "pendingClampGeneration"))
        assertNull(ReflectionHelpers.getField<android.animation.ValueAnimator?>(service, "clampAnimator"))

        state.value = state.value.copy(expanded = false, anchorEnd = true)
        shadowOfMain().idleFor(Duration.ofMillis(64))

        assertNull(ReflectionHelpers.getField<Long?>(service, "pendingClampGeneration"))
        assertNull(ReflectionHelpers.getField<android.animation.ValueAnimator?>(service, "clampAnimator"))
        assertTrue(requireNotNull(window.geometry.value).anchorEnd)
        controller.destroy()
    }

    @Test
    fun `hardware clamp starts only after the viewport frame commit and ignores stale commits`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup

        ReflectionHelpers.setField(service, "preciseY", 10_000f)
        params(service).y = 10_000
        state.value = BubbleUiState(expanded = true, anchorEnd = false)
        shadowOfMain().idleFor(Duration.ofMillis(48))

        assertEquals(params(service).width, root.width)
        assertEquals(params(service).height, root.height)
        assertNotNull(ReflectionHelpers.getField<Long?>(service, "pendingClampGeneration"))
        assertNull(ReflectionHelpers.getField<android.animation.ValueAnimator?>(service, "clampAnimator"))
        val callbacks = ReflectionHelpers.callInstanceMethod<ArrayList<Runnable>>(
            root.viewTreeObserver,
            "captureFrameCommitCallbacks",
        )
        assertNotNull(callbacks)
        assertFalse(requireNotNull(callbacks).isEmpty())
        val committedFrame = requireNotNull(callbacks).first()

        committedFrame.run()
        shadowOfMain().idle()
        assertNotNull(ReflectionHelpers.getField<android.animation.ValueAnimator?>(service, "clampAnimator"))

        val time = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 24f, 24f, 0)
        try {
            root.onInterceptTouchEvent(down)
            assertNull(ReflectionHelpers.getField<android.animation.ValueAnimator?>(service, "clampAnimator"))
            committedFrame.run()
            shadowOfMain().idle()
            assertNull(ReflectionHelpers.getField<android.animation.ValueAnimator?>(service, "clampAnimator"))
        } finally {
            down.recycle()
        }
        controller.destroy()
    }

    @Test
    fun `surface translation waits for the native viewport layout on resize and anchor change`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup
        val surfaceView = root.getChildAt(0)
        val density = service.resources.displayMetrics.density
        val collapsedWidth = root.width
        val collapsedTranslation = surfaceView.translationX

        state.value = BubbleUiState(expanded = true, anchorEnd = true)
        assertEquals(collapsedTranslation, surfaceView.translationX)
        assertEquals(collapsedWidth, root.width)
        assertChipDownTracks(root, 24f * density, 24f * density)
        shadowOfMain().idleFor(Duration.ofMillis(250))

        val expandedWidth = root.width
        val rightTranslation = ((requireNotNull(window.geometry.value).windowWidthDp - 72 - 272) * density)
        assertTrue(expandedWidth > collapsedWidth)
        assertEquals(rightTranslation, surfaceView.translationX)
        assertChipDownTracks(root, expandedWidth - 42f * density, 42f * density)

        state.value = state.value.copy(anchorEnd = false)
        assertEquals(rightTranslation, surfaceView.translationX)
        shadowOfMain().idleFor(Duration.ofMillis(250))

        assertEquals(expandedWidth, root.width)
        assertEquals(-272f * density, surfaceView.translationX)
        controller.destroy()
    }

    private fun assertChipDownTracks(root: ViewGroup, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        val down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, x, y, 0)
        val cancel = MotionEvent.obtain(now, now + 1, MotionEvent.ACTION_CANCEL, x, y, 0)
        try {
            root.onInterceptTouchEvent(down)
            assertTrue(ReflectionHelpers.getField<Boolean>(root, "tracking"))
            root.onInterceptTouchEvent(cancel)
            assertFalse(ReflectionHelpers.getField<Boolean>(root, "tracking"))
        } finally {
            down.recycle()
            cancel.recycle()
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
            assertTrue(root.onTouchEvent(smallMove))
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
    fun `legacy resize callback posted from worker thread cannot override native viewport`() {
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

        assertEquals(originalWidth, params(service).width)
        controller.destroy()
    }

    @Test
    fun `worker thread drag callback is marshalled before changing overlay position`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val initial = params(service).x
        val move = requireNotNull(window.move)
        val worker = Thread { move(-18f, 12f) }
        worker.start()
        worker.join()
        shadowOfMain().idle()

        assertEquals(initial - 18, params(service).x)
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
        assertTrue(params(service).flags and WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN != 0)
        assertTrue(params(service).flags and WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS != 0)
        assertEquals(
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_DEFAULT,
            params(service).layoutInDisplayCutoutMode,
        )
        assertTrue(params(service).x >= 12)
        assertTrue(params(service).y >= 8)
        window.resize?.invoke(120f, 96f)
        shadowOfMain().idle()
        assertEquals((72f * service.resources.displayMetrics.density).toInt(), params(service).width)
        service.onConfigurationChanged(Configuration())
        shadowOfMain().idle()
        controller.destroy()
        assertNull(window.move)
    }

    @Test
    @Config(sdk = [28])
    fun `chip down interrupts clamp before slop while panel down leaves it running`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = requireNotNull(ReflectionHelpers.getField<View?>(service, "root")) as ViewGroup

        ReflectionHelpers.setField(service, "preciseY", 10_000f)
        params(service).y = 10_000
        state.value = BubbleUiState(expanded = true, anchorEnd = false)
        shadowOfMain().idleFor(Duration.ofMillis(64))
        assertEquals(params(service).width, root.width)
        assertEquals(params(service).height, root.height)
        val animator = ReflectionHelpers.getField<android.animation.ValueAnimator?>(service, "clampAnimator")
        assertNotNull(animator)

        val time = SystemClock.uptimeMillis()
        val panelDown = MotionEvent.obtain(time, time, MotionEvent.ACTION_DOWN, 140f, 50f, 0)
        val panelUp = MotionEvent.obtain(time, time + 10, MotionEvent.ACTION_UP, 140f, 50f, 0)
        val chipDown = MotionEvent.obtain(time + 20, time + 20, MotionEvent.ACTION_DOWN, 24f, 24f, 0)
        val chipUp = MotionEvent.obtain(time + 30, time + 30, MotionEvent.ACTION_UP, 24f, 24f, 0)
        try {
            assertFalse(root.onInterceptTouchEvent(panelDown))
            assertSame(animator, ReflectionHelpers.getField<android.animation.ValueAnimator?>(service, "clampAnimator"))
            assertFalse(root.onInterceptTouchEvent(panelUp))

            val xAtPress = ReflectionHelpers.getField<Float>(service, "preciseX")
            val yAtPress = ReflectionHelpers.getField<Float>(service, "preciseY")
            assertFalse(root.onInterceptTouchEvent(chipDown))
            assertNull(ReflectionHelpers.getField<android.animation.ValueAnimator?>(service, "clampAnimator"))
            assertTrue(root.onTouchEvent(chipDown))
            assertTrue(root.onTouchEvent(chipUp))
            shadowOfMain().idleFor(Duration.ofMillis(250))

            assertEquals(xAtPress, ReflectionHelpers.getField<Float>(service, "preciseX"))
            assertEquals(yAtPress, ReflectionHelpers.getField<Float>(service, "preciseY"))
            coVerify(exactly = 0) { preferences.setSpot(any(), any()) }
        } finally {
            panelDown.recycle()
            panelUp.recycle()
            chipDown.recycle()
            chipUp.recycle()
            controller.destroy()
        }
    }

    @Test
    fun `manual safe inset clamp keeps expanded panel within nonzero system bar bounds`() {
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val realWindowManager = ReflectionHelpers.getField<WindowManager>(service, "windowManager")
        val density = service.resources.displayMetrics.density
        val bounds = Rect(0, 0, (800 * density).toInt(), (1000 * density).toInt())
        val systemBars = WindowInsets.Type.systemBars()
        val safeInsets = Insets.of(
            (20 * density).toInt(),
            (40 * density).toInt(),
            (30 * density).toInt(),
            (60 * density).toInt(),
        )
        val insets = WindowInsets.Builder()
            .setInsets(systemBars, safeInsets)
            .setInsetsIgnoringVisibility(systemBars, safeInsets)
            .setVisible(systemBars, true)
            .build()
        val measuredWindowManager = mockk<WindowManager>(relaxed = true)
        every { measuredWindowManager.currentWindowMetrics } returns WindowMetrics(bounds, insets, density)
        ReflectionHelpers.setField(service, "windowManager", measuredWindowManager)

        try {
            state.value = BubbleUiState(expanded = true, phase = BubblePhase.Copied, anchorEnd = false)
            shadowOfMain().idleFor(Duration.ofMillis(250))
            ReflectionHelpers.setField(service, "preciseX", bounds.right.toFloat())
            ReflectionHelpers.setField(service, "preciseY", bounds.bottom.toFloat())
            service.onConfigurationChanged(Configuration())
            shadowOfMain().idleFor(Duration.ofMillis(250))

            val expectedMaxX = bounds.right - safeInsets.right - (12 * density).toInt() - params(service).width
            val expectedMaxY = bounds.bottom - safeInsets.bottom - (12 * density).toInt() - params(service).height
            assertEquals(expectedMaxX, params(service).x)
            assertEquals(expectedMaxX, ReflectionHelpers.getField<Float>(service, "preciseX").toInt())
            assertEquals(expectedMaxY, ReflectionHelpers.getField<Float>(service, "preciseY").toInt())
            assertTrue(params(service).y >= (40 * density).toInt() + (12 * density).toInt())
            assertEquals(0, params(service).fitInsetsTypes)
        } finally {
            ReflectionHelpers.setField(service, "windowManager", realWindowManager)
            controller.destroy()
        }
    }

    private fun shadowOfMain() = org.robolectric.Shadows.shadowOf(Looper.getMainLooper())
    @Test fun `appearance changes keep the same surface and result while native hit geometry follows size`() {
        val appearance = MutableStateFlow(BubbleAppearance())
        every { preferences.appearance } returns appearance
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val root = ReflectionHelpers.getField<android.widget.FrameLayout>(service, "root")
        val originalSurface = root.getChildAt(0)
        assertEquals(72, window.geometry.value?.chipSizeDp)
        val right = params(service).x + params(service).width
        state.value = state.value.copy(phase = BubblePhase.Copied, previewPath = "/cache/result.png", editReady = true)
        shadowOfMain().idle()
        appearance.value = BubbleAppearance(80, 0.5f, true, true)
        shadowOfMain().idle()
        assertEquals(88, window.geometry.value?.chipSizeDp)
        assertEquals(right, params(service).x + params(service).width)
        assertSame(originalSurface, root.getChildAt(0))
        assertEquals("/cache/result.png", state.value.previewPath)
        val down = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 10f, 10f, 0)
        root.dispatchTouchEvent(down); assertTrue(window.interacting.value); down.recycle()
        val up = MotionEvent.obtain(0, 10, MotionEvent.ACTION_UP, 10f, 10f, 0)
        root.dispatchTouchEvent(up); assertFalse(window.interacting.value); up.recycle()
        appearance.value = appearance.value.copy(opacity = 0.6f)
        shadowOfMain().idle()
        assertSame(originalSurface, root.getChildAt(0))
        controller.destroy()
        assertFalse(window.interacting.value)
    }

    @Test fun `appearance resizing preserves a left anchored chip edge`() {
        state.value = BubbleUiState(anchorEnd = false)
        val appearance = MutableStateFlow(BubbleAppearance())
        every { preferences.appearance } returns appearance
        val controller = Robolectric.buildService(BubbleService::class.java)
        val service = controller.get()
        prepare(service)
        controller.create()
        shadowOfMain().idle()
        val left = params(service).x

        appearance.value = BubbleAppearance(sizeDp = 80)
        shadowOfMain().idle()

        assertEquals(left, params(service).x)
        assertEquals(88, window.geometry.value?.chipSizeDp)
        controller.destroy()
    }

}
