package dev.kigya.cutout.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.WindowInsets
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.lifecycle.LifecycleService
import androidx.lifecycle.lifecycleScope
import dev.kigya.cutout.AppScope
import dev.kigya.cutout.EraseApp
import dev.kigya.cutout.MainActivity
import dev.kigya.cutout.locale.AppLocales
import dev.kigya.cutout.R
import dev.kigya.cutout.data.BubblePreferences
import com.facebook.react.interfaces.fabric.ReactSurface
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlin.math.roundToInt
import kotlin.math.hypot
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@AndroidEntryPoint
class BubbleService : LifecycleService() {
    @Inject lateinit var preferences: BubblePreferences
    @Inject lateinit var window: BubbleWindow
    @Inject lateinit var presenter: BubblePresenter
    @Inject lateinit var appScope: AppScope

    private lateinit var windowManager: WindowManager
    private lateinit var params: WindowManager.LayoutParams
    private val mainHandler = Handler(Looper.getMainLooper())
    private var root: OverlayRoot? = null
    private var surface: ReactSurface? = null
    private var latestState = BubbleUiState()
    private var acknowledgedGeometryRevision = -1L
    private var geometryKey: GeometryKey? = null
    private var clampAnimator: ValueAnimator? = null
    private var geometryTransitionGeneration = 0L
    private var pendingClampGeneration: Long? = null
    private var awaitingClampCommitGeneration: Long? = null
    private var preciseX = 0f
    private var preciseY = 0f
    private var layoutFramePosted = false
    private var pendingSurfaceTranslationX: Float? = null
    private val layoutFrameCallback = Runnable {
        layoutFramePosted = false
        val translationAfterLayout = pendingSurfaceTranslationX
        pendingSurfaceTranslationX = null
        updateLayoutNow()
        translationAfterLayout?.let { translationX ->
            root?.stageSurfaceTranslationAfterLayout(translationX)
        }
    }

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLocales.wrap(newBase))
    }

    override fun onCreate() {
        super.onCreate()
        windowManager = getSystemService(WindowManager::class.java)
        createChannel()
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return
        }
        try {
            attach()
            val foregroundType = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                NOTIFICATION_ID,
                notification(),
                foregroundType,
            )
        } catch (error: Exception) {
            Log.e(TAG, "foreground start failed", error)
            detach()
            stopSelf()
            return
        }
        lifecycleScope.launch {
            preferences.spot.first()?.let { spot ->
                val frame = frame()
                val chip = bubblePx()
                val margin = dp(MARGIN_DP)
                val chipX = spot.x.roundToInt().coerceIn(
                    frame.left + margin,
                    (frame.width - frame.right - margin - chip).coerceAtLeast(frame.left + margin),
                )
                val anchorEnd = chipX + chip / 2 > frame.width / 2
                presenter.setAnchorEnd(anchorEnd)
                latestState = latestState.copy(anchorEnd = anchorEnd)
                preciseX = chipX.toFloat()
                preciseY = spot.y
                applyGeometry(latestState, forcePublish = true, animateClamp = false)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_HIDE) {
            appScope.launch { preferences.setEnabled(false) }
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        if (!Settings.canDrawOverlays(this)) {
            stopSelf()
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onDestroy() {
        cancelClampAnimation()
        root?.removeCallbacks(layoutFrameCallback)
        layoutFramePosted = false
        pendingSurfaceTranslationX = null
        window.move = null
        window.settle = null
        window.resize = null
        window.acknowledgeGeometry = null
        window.clearGeometry()
        detach()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::params.isInitialized) {
            cancelClampAnimation()
            applyGeometry(latestState, forcePublish = true)
        }
    }

    private fun attach() {
        val frame = frame()
        val bubble = bubblePx()
        latestState = presenter.state.value
        val chipX = if (latestState.anchorEnd) {
            frame.width - frame.right - dp(MARGIN_DP) - bubble
        } else {
            frame.left + dp(MARGIN_DP)
        }
        val viewport = viewport(latestState, frame)
        params = WindowManager.LayoutParams(
            dp(viewport.first),
            dp(viewport.second),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.LEFT
            x = windowOriginForChip(chipX.toFloat(), latestState)
            y = frame.height / 3
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                // WindowManager otherwise adds its own move animation while the
                // service is already animating and synchronizing the viewport.
                setCanPlayMoveAnimation(false)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // BubbleService owns physical coordinates and safe-inset clamping.
                // Do not add a second system-bars origin or framework cutout fit.
                setFitInsetsTypes(0)
                layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
            }
        }
        preciseX = chipX.toFloat()
        preciseY = params.y.toFloat()
        clampChipImmediately(frame)
        window.move = { dx, dy -> dragBy(dx, dy) }
        window.settle = {
            dragEnd()
            latestState.anchorEnd
        }
        // Kept as a no-op for old bridge clients. Window size is now derived only
        // from Presenter state so JS cannot race native layout ownership.
        window.resize = { _, _ -> }
        window.acknowledgeGeometry = { revision -> acknowledgeGeometry(revision) }
        val host = (application as EraseApp).reactHost
        host.start()
        val created = host.createSurface(this, "Bubble", null)
        val view = created.view ?: error("bubble surface")
        view.setBackgroundColor(Color.TRANSPARENT)
        val surfaceWidth = dp(SURFACE_WIDTH_DP)
        val surfaceHeight = dp(SURFACE_HEIGHT_DP)
        val exactWidth = View.MeasureSpec.makeMeasureSpec(surfaceWidth, View.MeasureSpec.EXACTLY)
        val exactHeight = View.MeasureSpec.makeMeasureSpec(surfaceHeight, View.MeasureSpec.EXACTLY)
        view.measure(exactWidth, exactHeight)
        view.layout(0, 0, surfaceWidth, surfaceHeight)
        created.start()
        surface = created
        val container = OverlayRoot(
            context = this,
            chipPx = bubble,
            // Hit-test against the translation actually committed to the view.
            // A requested anchor can be pending until WindowManager lays out the
            // new viewport, so latestState would move the touch target early.
            chipLeft = { view.translationX + dp(CHIP_SURFACE_LEFT_DP) },
            slop = ViewConfiguration.get(this).scaledTouchSlop,
            onChipDown = { cancelClampAnimation() },
            onDragStart = { cancelClampAnimation() },
            onViewportLayout = { onTargetViewportLaidOut() },
            onDrag = { dx, dy -> dragBy(dx, dy) },
            onDrop = { dragEnd() },
            onCancel = {
                cancelClampAnimation()
                dragEnd()
            },
        ).apply {
            // The surface uses physical coordinates; localized text handles its own direction.
            layoutDirection = View.LAYOUT_DIRECTION_LTR
            clipChildren = true
            clipToPadding = true
            alpha = 0f
            view.translationX = surfaceTranslationX(latestState)
            addView(
                view,
                android.widget.FrameLayout.LayoutParams(
                    surfaceWidth,
                    surfaceHeight,
                    Gravity.TOP or Gravity.LEFT,
                ),
            )
        }
        root = container
        windowManager.addView(container, params)
        applyGeometry(latestState, forcePublish = true, animateClamp = false)
        container.post { container.alpha = 1f }
        lifecycleScope.launch {
            presenter.state.collect { state ->
                latestState = state
                applyGeometry(state)
            }
        }
    }

    private fun windowFlags(): Int {
        // Keep the WindowManager origin physical-screen relative. BubbleService
        // clamps the whole viewport against its own bar/cutout-safe display frame.
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
            WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
    }

    private fun dragBy(dx: Float, dy: Float) {
        onMain {
            if (!::params.isInitialized) return@onMain
            cancelClampAnimation()
            preciseX += dx
            preciseY += dy
            clampChipImmediately(frame(), fitPanel = false)
            scheduleLayoutUpdate()
        }
    }

    private fun dragEnd() {
        onMain {
            if (!::params.isInitialized) return@onMain
            cancelClampAnimation()
            val frame = frame()
            val bubble = bubblePx()
            val chipX = preciseX
            val bubbleCenter = chipX + bubble / 2f
            val anchorEnd = bubbleCenter > frame.width / 2f
            val anchorChanged = anchorEnd != latestState.anchorEnd
            presenter.setAnchorEnd(anchorEnd)
            latestState = latestState.copy(anchorEnd = anchorEnd)
            preciseX = if (anchorEnd) {
                (frame.width - frame.right - dp(MARGIN_DP) - bubblePx()).toFloat()
            } else {
                (frame.left + dp(MARGIN_DP)).toFloat()
            }
            clampChipImmediately(frame)
            if (anchorChanged) {
                applyGeometry(latestState, animateClamp = false)
            } else {
                scheduleLayoutUpdate()
            }
            lifecycleScope.launch {
                preferences.setSpot(preciseX, preciseY)
            }
        }
    }

    private fun scheduleLayoutUpdate() {
        val view = root ?: return
        if (!view.isAttachedToWindow) return
        if (layoutFramePosted) return
        layoutFramePosted = true
        view.postOnAnimation(layoutFrameCallback)
    }

    private fun updateLayoutNow() {
        val view = root ?: return
        if (!view.isAttachedToWindow) return
        windowManager.updateViewLayout(view, params)
    }

    private fun onTargetViewportLaidOut() {
        val generation = pendingClampGeneration ?: return
        val view = root ?: return
        if (view.width != params.width || view.height != params.height) return
        if (awaitingClampCommitGeneration == generation) return
        awaitingClampCommitGeneration = generation

        val startAfterViewportFrame = Runnable {
            mainHandler.post {
                if (pendingClampGeneration != generation || geometryTransitionGeneration != generation) return@post
                val currentRoot = root ?: return@post
                if (currentRoot.width != params.width || currentRoot.height != params.height) return@post
                pendingClampGeneration = null
                awaitingClampCommitGeneration = null
                animateClampToSafeBounds(frame())
            }
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && view.isHardwareAccelerated && view.viewTreeObserver.isAlive) {
            // Do not spend the clamp duration behind the old-size buffer. The
            // first committed frame must contain the new WindowManager viewport.
            view.viewTreeObserver.registerFrameCommitCallback(startAfterViewportFrame)
        } else {
            // API 26–28 do not expose a hardware frame-commit callback.
            view.postOnAnimation { startAfterViewportFrame.run() }
        }
    }

    private fun onMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    private fun detach() {
        surface?.stop()
        surface = null
        root?.let { view ->
            if (view.isAttachedToWindow) windowManager.removeView(view)
        }
        root = null
    }

    private data class Frame(
        val width: Int,
        val height: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private fun frame(): Frame {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val metrics = windowManager.currentWindowMetrics
            val bars = metrics.windowInsets.getInsetsIgnoringVisibility(
                WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout(),
            )
            return Frame(metrics.bounds.width(), metrics.bounds.height(), bars.left, bars.top, bars.right, bars.bottom)
        }
        val point = android.graphics.Point()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealSize(point)
        val statusId = resources.getIdentifier("status_bar_height", "dimen", "android")
        val navId = resources.getIdentifier("navigation_bar_height", "dimen", "android")
        val top = if (statusId > 0) resources.getDimensionPixelSize(statusId) else dp(24)
        val bottom = if (navId > 0) resources.getDimensionPixelSize(navId) else dp(48)
        return Frame(point.x, point.y, 0, top, 0, bottom)
    }

    private data class GeometryKey(
        val expanded: Boolean,
        val phase: BubblePhase,
        val editReady: Boolean,
        val anchorEnd: Boolean,
        val frameWidth: Int,
        val frameHeight: Int,
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int,
    )

    private fun applyGeometry(
        state: BubbleUiState,
        forcePublish: Boolean = false,
        animateClamp: Boolean = true,
    ) {
        if (!::params.isInitialized) return
        val display = frame()
        val nextKey = GeometryKey(
            expanded = state.expanded,
            phase = state.phase,
            editReady = state.editReady,
            anchorEnd = state.anchorEnd,
            frameWidth = display.width,
            frameHeight = display.height,
            left = display.left,
            top = display.top,
            right = display.right,
            bottom = display.bottom,
        )
        val changed = geometryKey != nextKey
        if (!changed && !forcePublish) {
            latestState = state
            return
        }
        // Geometry transitions supersede any old clamp, even when the new target
        // already fits. preciseX/Y are the current visual position and remain the
        // canonical point while the WindowManager frame catches up.
        cancelClampAnimation()
        latestState = state
        val chipX = preciseX
        val y = preciseY
        applyViewportSize()
        queueSurfaceTranslation()
        preciseX = chipX
        preciseY = y
        params.x = windowOriginForChip(chipX, latestState)
        params.y = y.roundToInt()
        if (changed && animateClamp) {
            val (targetX, targetY) = clampTarget(display)
            if (chipX != targetX || y != targetY) {
                pendingClampGeneration = geometryTransitionGeneration
            } else {
                pendingClampGeneration = null
                awaitingClampCommitGeneration = null
                clampChipImmediately(display)
            }
        } else {
            pendingClampGeneration = null
            awaitingClampCommitGeneration = null
            clampChipImmediately(display)
        }
        scheduleLayoutUpdate()
        geometryKey = nextKey
        if (changed || forcePublish) publishGeometry(state, force = true)
    }

    private fun applyViewportSize(display: Frame = frame()) {
        val (widthDp, heightDp) = viewport(latestState, display)
        params.width = dp(widthDp)
        params.height = dp(heightDp)
    }

    private fun viewport(state: BubbleUiState, display: Frame = frame()): Pair<Int, Int> =
        if (state.expanded) expandedWidthDp(display) to phaseHeight(state, display) else CHIP_SIZE_DP to CHIP_SIZE_DP

    private fun phaseHeight(state: BubbleUiState, display: Frame = frame()): Int = minOf(when (state.phase) {
        BubblePhase.Working -> WORKING_HEIGHT_DP
        BubblePhase.Copied -> RESULT_HEIGHT_DP
        BubblePhase.Failed -> if (state.editReady) RESULT_HEIGHT_DP else IDLE_HEIGHT_DP
        BubblePhase.Idle -> IDLE_HEIGHT_DP
    }, availableHeightDp(display))

    private fun expandedWidthDp(display: Frame): Int = minOf(EXPANDED_WIDTH_DP, availableWidthDp(display))

    private fun panelWidthDp(state: BubbleUiState, display: Frame): Int =
        if (state.expanded) (expandedWidthDp(display) - CHIP_SIZE_DP - GAP_DP).coerceAtLeast(0) else PANEL_WIDTH_DP

    private fun availableWidthDp(display: Frame): Int =
        ((display.width - display.left - display.right - 2 * dp(MARGIN_DP)).coerceAtLeast(0) /
            resources.displayMetrics.density).roundToInt().coerceAtLeast(CHIP_SIZE_DP)

    private fun availableHeightDp(display: Frame): Int =
        ((display.height - display.top - display.bottom - 2 * dp(MARGIN_DP)).coerceAtLeast(0) /
            resources.displayMetrics.density).roundToInt().coerceAtLeast(CHIP_SIZE_DP)

    private fun visibleChipLeft(state: BubbleUiState): Float =
        if (state.expanded && state.anchorEnd) dp(viewport(state).first - CHIP_SIZE_DP).toFloat() else 0f

    private fun surfaceTranslationX(state: BubbleUiState): Float =
        visibleChipLeft(state) - dp(CHIP_SURFACE_LEFT_DP)

    private fun queueSurfaceTranslation() {
        pendingSurfaceTranslationX = surfaceTranslationX(latestState)
        scheduleLayoutUpdate()
    }

    private fun publishGeometry(state: BubbleUiState, force: Boolean) {
        if (!force) return
        val display = frame()
        val (windowWidth, windowHeight) = viewport(state, display)
        window.publishGeometry(
            BubbleGeometry(
                revision = window.nextGeometryRevision(),
                expanded = state.expanded,
                anchorEnd = state.anchorEnd,
                windowWidthDp = windowWidth,
                windowHeightDp = windowHeight,
                surfaceWidthDp = SURFACE_WIDTH_DP,
                surfaceHeightDp = SURFACE_HEIGHT_DP,
                chipSizeDp = CHIP_SIZE_DP,
                panelWidthDp = panelWidthDp(state, display),
                panelHeightDp = phaseHeight(state, display),
                chipLeftDp = CHIP_SURFACE_LEFT_DP,
                chipTopDp = 0,
            ),
        )
    }

    private fun acknowledgeGeometry(revision: Long) {
        val activeRevision = window.geometry.value?.revision ?: return
        if (revision == activeRevision && revision > acknowledgedGeometryRevision) {
            acknowledgedGeometryRevision = revision
        }
    }

    private fun windowOriginForChip(chipX: Float, state: BubbleUiState): Int =
        (chipX - if (state.expanded && state.anchorEnd) dp(viewport(state).first - CHIP_SIZE_DP) else 0).roundToInt()

    private fun clampChipImmediately(display: Frame, fitPanel: Boolean = true) {
        val (targetX, targetY) = clampTarget(display, fitPanel)
        preciseX = targetX
        preciseY = targetY
        params.x = windowOriginForChip(preciseX, latestState)
        params.y = preciseY.roundToInt()
    }

    private fun clampTarget(display: Frame, fitPanel: Boolean = true): Pair<Float, Float> {
        val margin = dp(MARGIN_DP)
        val right = display.width - display.right - margin
        val minX = display.left + margin + if (fitPanel) expandedLeftExtentPx() else 0
        val rightExtent = if (fitPanel) expandedRightExtentPx() else bubblePx()
        val maxX = (right - rightExtent).coerceAtLeast(minX)
        val minY = display.top + margin
        val visibleHeight = if (fitPanel) params.height else bubblePx()
        val maxY = (display.height - display.bottom - margin - visibleHeight).coerceAtLeast(minY)
        return preciseX.coerceIn(minX.toFloat(), maxX.toFloat()) to
            preciseY.coerceIn(minY.toFloat(), maxY.toFloat())
    }

    private fun animateClampToSafeBounds(display: Frame) {
        val fromX = preciseX
        val fromY = preciseY
        val (toX, toY) = clampTarget(display)
        if (fromX == toX && fromY == toY) {
            preciseX = toX
            preciseY = toY
            clampChipImmediately(display)
            return
        }
        cancelClampAnimation()
        clampAnimator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = CLAMP_DURATION_MS
            addUpdateListener { animation ->
                val fraction = animation.animatedValue as Float
                preciseX = fromX + (toX - fromX) * fraction
                preciseY = fromY + (toY - fromY) * fraction
                params.x = windowOriginForChip(preciseX, latestState)
                params.y = preciseY.roundToInt()
                scheduleLayoutUpdate()
            }
            start()
        }
    }

    private fun cancelClampAnimation() {
        geometryTransitionGeneration += 1
        pendingClampGeneration = null
        awaitingClampCommitGeneration = null
        clampAnimator?.cancel()
        clampAnimator = null
    }

    private fun expandedLeftExtentPx(): Int =
        if (latestState.expanded && latestState.anchorEnd) dp(viewport(latestState).first - CHIP_SIZE_DP) else 0

    private fun expandedRightExtentPx(): Int = when {
        !latestState.expanded -> bubblePx()
        latestState.anchorEnd -> dp(CHIP_SIZE_DP)
        else -> dp(viewport(latestState).first)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun bubblePx(): Int = dp(CHIP_SIZE_DP)

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(CHANNEL_ID, getString(R.string.channel_name), NotificationManager.IMPORTANCE_MIN)
        channel.description = getString(R.string.channel_description)
        channel.setSound(null, null)
        manager.createNotificationChannel(channel)
    }

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            1,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val hide = PendingIntent.getService(
            this,
            2,
            Intent(this, BubbleService::class.java).setAction(ACTION_HIDE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_bubble)
            .setContentTitle(getString(R.string.notification_title))
            .setContentText(getString(R.string.notification_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setContentIntent(open)
            .addAction(0, getString(R.string.notification_hide), hide)
            .build()
    }

    companion object {
        const val ACTION_HIDE = "dev.kigya.cutout.HIDE"
        private const val CHANNEL_ID = "bubble"
        private const val NOTIFICATION_ID = 27
        private const val TAG = "BubbleService"
        private const val CHIP_SIZE_DP = 84
        private const val CHIP_SURFACE_LEFT_DP = 272
        private const val SURFACE_WIDTH_DP = 628
        private const val SURFACE_HEIGHT_DP = 400
        private const val PANEL_WIDTH_DP = 260
        private const val EXPANDED_WIDTH_DP = 356
        private const val IDLE_HEIGHT_DP = 260
        private const val WORKING_HEIGHT_DP = 340
        private const val RESULT_HEIGHT_DP = 400
        private const val MARGIN_DP = 12
        private const val GAP_DP = 12
        private const val CLAMP_DURATION_MS = 200L
    }
}

private class OverlayRoot(
    context: android.content.Context,
    private val chipPx: Int,
    private val chipLeft: () -> Float,
    private val slop: Int,
    private val onChipDown: () -> Unit,
    private val onDragStart: () -> Unit,
    private val onViewportLayout: () -> Unit,
    private val onDrag: (Float, Float) -> Unit,
    private val onDrop: () -> Unit,
    private val onCancel: () -> Unit,
) : android.widget.FrameLayout(context) {
    private var tracking = false
    private var dragging = false
    private var translationAfterLayout: Float? = null
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f
    private var activePointerId = MotionEvent.INVALID_POINTER_ID

    fun stageSurfaceTranslationAfterLayout(translationX: Float) {
        translationAfterLayout = translationX
        requestLayout()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        translationAfterLayout?.let { translationX ->
            getChildAt(0)?.translationX = translationX
            translationAfterLayout = null
        }
        onViewportLayout()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) return true
        return super.dispatchTouchEvent(event)
    }

    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        // RN Pressability can request interception suppression from the chip.
        // Keep the native drag recognizer active for chip-originating gestures,
        // while allowing panel ScrollViews to retain normal nested scrolling.
        if (disallowIntercept && (tracking || dragging)) {
            super.requestDisallowInterceptTouchEvent(false)
        } else {
            super.requestDisallowInterceptTouchEvent(disallowIntercept)
        }
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTracking(event)
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return false
                val currentRawX = rawX(event, index)
                val currentRawY = rawY(event, index)
                if (!dragging && hypot(currentRawX - downRawX, currentRawY - downRawY) > slop) {
                    dragging = true
                    lastRawX = downRawX
                    lastRawY = downRawY
                    onDragStart()
                    return true
                }
                return dragging
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                // Keep tracking the original pointer by ID; its index may change.
                Unit
            }
            MotionEvent.ACTION_POINTER_UP -> switchActivePointer(event)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) return true
                tracking = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startTracking(event)
                return tracking
            }
            MotionEvent.ACTION_POINTER_UP -> switchActivePointer(event)
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                val index = event.findPointerIndex(activePointerId)
                if (index < 0) return true
                val currentRawX = rawX(event, index)
                val currentRawY = rawY(event, index)
                if (!dragging && hypot(currentRawX - downRawX, currentRawY - downRawY) > slop) {
                    dragging = true
                    lastRawX = downRawX
                    lastRawY = downRawY
                    onDragStart()
                }
                if (!dragging) return true
                val dx = currentRawX - lastRawX
                val dy = currentRawY - lastRawY
                lastRawX = currentRawX
                lastRawY = currentRawY
                onDrag(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                val wasTracking = tracking
                val wasDragging = dragging
                dragging = false
                tracking = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                if (wasDragging) {
                    if (event.actionMasked == MotionEvent.ACTION_CANCEL) {
                        onCancel()
                    } else {
                        onDrop()
                    }
                }
                return wasTracking || wasDragging
            }
        }
        return tracking || dragging
    }

    private fun startTracking(event: MotionEvent) {
        val wasTracking = tracking
        tracking = inDragZone(event.x, event.y)
        dragging = false
        if (tracking && !wasTracking) onChipDown()
        activePointerId = if (tracking) event.getPointerId(0) else MotionEvent.INVALID_POINTER_ID
        downRawX = rawX(event, 0)
        downRawY = rawY(event, 0)
        lastRawX = downRawX
        lastRawY = downRawY
    }

    private fun switchActivePointer(event: MotionEvent) {
        if (event.getPointerId(event.actionIndex) != activePointerId) return
        val nextIndex = if (event.actionIndex == 0) 1 else 0
        if (nextIndex >= event.pointerCount) {
            tracking = false
            dragging = false
            activePointerId = MotionEvent.INVALID_POINTER_ID
            onCancel()
            return
        }
        activePointerId = event.getPointerId(nextIndex)
        lastRawX = rawX(event, nextIndex)
        lastRawY = rawY(event, nextIndex)
        downRawX = lastRawX
        downRawY = lastRawY
    }

    private fun rawX(event: MotionEvent, index: Int): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return event.getRawX(index)
        if (index == 0) return event.rawX
        val location = IntArray(2)
        getLocationOnScreen(location)
        return location[0] + event.getX(index)
    }

    private fun rawY(event: MotionEvent, index: Int): Float {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) return event.getRawY(index)
        if (index == 0) return event.rawY
        val location = IntArray(2)
        getLocationOnScreen(location)
        return location[1] + event.getY(index)
    }

    private fun inDragZone(x: Float, y: Float): Boolean {
        val left = chipLeft()
        return x >= left && x <= left + chipPx && y >= 0f && y <= chipPx.toFloat()
    }
}
