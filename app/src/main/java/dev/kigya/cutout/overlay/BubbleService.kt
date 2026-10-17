package dev.kigya.cutout.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
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
import kotlin.math.hypot
import kotlin.math.roundToInt
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
    private var anchorEnd = true

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
                params.x = spot.x.roundToInt()
                params.y = spot.y.roundToInt()
                clamp(frame)
                updateLayout()
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
        window.move = null
        window.settle = null
        window.resize = null
        detach()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        if (::params.isInitialized) {
            clamp(frame())
            updateLayout()
        }
    }

    private fun attach() {
        val frame = frame()
        val bubble = bubblePx()
        anchorEnd = true
        params = WindowManager.LayoutParams(
            bubble,
            bubble,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            windowFlags(),
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = frame.width - bubble - frame.right - dp(16)
            y = frame.height / 3
        }
        clamp(frame)
        window.move = { dx, dy -> dragBy(dx, dy) }
        window.settle = {
            dragEnd()
            anchorEnd
        }
        window.resize = { widthDp, heightDp -> resizeTo(widthDp, heightDp) }
        val host = (application as EraseApp).reactHost
        host.start()
        val created = host.createSurface(this, "Bubble", null)
        val view = created.view ?: error("bubble surface")
        view.setBackgroundColor(Color.TRANSPARENT)
        val exact = View.MeasureSpec.makeMeasureSpec(bubble, View.MeasureSpec.EXACTLY)
        view.measure(exact, exact)
        view.layout(0, 0, bubble, bubble)
        created.start()
        surface = created
        val container = OverlayRoot(
            context = this,
            chipPx = bubble,
            closePx = dp(48),
            slop = dp(8),
            anchorEnd = { presenter.state.value.anchorEnd },
            onDrag = { dx, dy -> dragBy(dx, dy) },
            onDrop = { dragEnd() },
        ).apply {
            clipChildren = true
            clipToPadding = true
            alpha = 0f
            addView(
                view,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        root = container
        windowManager.addView(container, params)
        container.post { container.alpha = 1f }
        lifecycleScope.launch {
            var expanded: Boolean? = null
            presenter.state.collect { state ->
                if (expanded == state.expanded) return@collect
                expanded = state.expanded
                val chip = 84f
                if (state.expanded) resizeTo(chip + 12f + 260f, 260f) else resizeTo(chip, chip)
            }
        }
    }

    private fun windowFlags(): Int {
        return WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
    }

    private fun dragBy(dx: Float, dy: Float) {
        onMain {
            if (!::params.isInitialized) return@onMain
            params.x += dx.roundToInt()
            params.y += dy.roundToInt()
            clamp(frame())
            updateLayout()
        }
    }

    private fun resizeTo(widthDp: Float, heightDp: Float) {
        onMain {
            if (!::params.isInitialized) return@onMain
            val frame = frame()
            val width = dp(widthDp).coerceAtLeast(bubblePx())
            val height = dp(heightDp).coerceAtLeast(bubblePx())
            val right = params.x + params.width
            params.width = width
            params.height = height
            if (presenter.state.value.anchorEnd) params.x = right - width
            clamp(frame)
            updateLayout()
        }
    }

    private fun dragEnd() {
        onMain {
            if (!::params.isInitialized) return@onMain
            val frame = frame()
            val bubble = bubblePx()
            val bubbleCenter = if (presenter.state.value.anchorEnd) {
                params.x + params.width - bubble / 2
            } else {
                params.x + bubble / 2
            }
            anchorEnd = bubbleCenter > frame.width / 2
            presenter.setAnchorEnd(anchorEnd)
            val width = params.width.takeIf { it > 0 } ?: bubble
            params.x = if (anchorEnd) frame.width - width - frame.right - dp(12) else frame.left + dp(12)
            clamp(frame)
            updateLayout()
            lifecycleScope.launch {
                preferences.setSpot(params.x.toFloat(), params.y.toFloat())
            }
        }
    }

    private fun updateLayout() {
        val view = root ?: return
        if (!view.isAttachedToWindow) return
        windowManager.updateViewLayout(view, params)
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

    private fun clamp(frame: Frame) {
        val margin = dp(8)
        val minX = frame.left + margin
        val minY = frame.top + margin
        val maxX = (frame.width - params.width - frame.right - margin).coerceAtLeast(minX)
        val maxY = (frame.height - params.height - frame.bottom - margin).coerceAtLeast(minY)
        params.x = params.x.coerceIn(minX, maxX)
        params.y = params.y.coerceIn(minY, maxY)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density).roundToInt()

    private fun bubblePx(): Int = dp(88)

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
    }
}

private class OverlayRoot(
    context: android.content.Context,
    private val chipPx: Int,
    private val closePx: Int,
    private val slop: Int,
    private val anchorEnd: () -> Boolean,
    private val onDrag: (Float, Float) -> Unit,
    private val onDrop: () -> Unit,
) : android.widget.FrameLayout(context) {
    private var tracking = false
    private var dragging = false
    private var downRawX = 0f
    private var downRawY = 0f
    private var lastRawX = 0f
    private var lastRawY = 0f

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_OUTSIDE) return true
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tracking = inDragZone(event.x, event.y)
                dragging = false
                downRawX = event.rawX
                downRawY = event.rawY
                lastRawX = event.rawX
                lastRawY = event.rawY
            }
            MotionEvent.ACTION_MOVE -> {
                if (!tracking) return false
                if (hypot(event.rawX - downRawX, event.rawY - downRawY) > slop) {
                    dragging = true
                    lastRawX = event.rawX
                    lastRawY = event.rawY
                    return true
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> tracking = false
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return false
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - lastRawX
                val dy = event.rawY - lastRawY
                lastRawX = event.rawX
                lastRawY = event.rawY
                onDrag(dx, dy)
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                tracking = false
                onDrop()
            }
        }
        return true
    }

    private fun inDragZone(x: Float, y: Float): Boolean {
        val left = if (anchorEnd()) (width - chipPx).coerceAtLeast(0) else 0
        val inChip = x >= left && x <= left + chipPx && y >= 0f && y <= chipPx.toFloat()
        val inClose = x >= left + chipPx - closePx && x <= left + chipPx && y >= 0f && y <= closePx.toFloat()
        return inChip && !inClose
    }
}
