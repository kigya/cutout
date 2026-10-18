package dev.kigya.cutout.overlay

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import dev.kigya.cutout.data.ClipboardImages
import dev.kigya.cutout.data.ClipboardLook
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class ClipboardActivity : ComponentActivity() {
    @Inject lateinit var clipboard: ClipboardImages
    @Inject lateinit var bus: ClipboardBus

    private var delivered = false
    private val handler = Handler(Looper.getMainLooper())
    private val timeout = Runnable { complete(ClipboardLook.NoFocus) }
    private val read = Runnable {
        if (!delivered && !isFinishing && hasWindowFocus()) {
            complete(runCatching { clipboard.peek() }.getOrElse { ClipboardLook.Unreadable })
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val requestId = intent.getStringExtra(EXTRA_REQUEST)
        if (requestId == null || !bus.attachForeground(this, requestId)) {
            delivered = true
            finish()
            return
        }
        handler.postDelayed(timeout, 2_000L)
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            handler.removeCallbacks(read)
            // Let WindowManager commit the focus before ClipboardService checks it.
            handler.postDelayed(read, 80)
        } else handler.removeCallbacks(read)
    }

    private fun complete(look: ClipboardLook) {
        if (delivered || isFinishing) return
        delivered = true
        handler.removeCallbacksAndMessages(null)
        val consume = intent.getBooleanExtra(EXTRA_CONSUME, false)
        bus.offer(ClipboardRead(look, consume, intent.getStringExtra(EXTRA_REQUEST)))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        if (!delivered && !isChangingConfigurations) {
            bus.offer(ClipboardRead(ClipboardLook.NoFocus, true, intent.getStringExtra(EXTRA_REQUEST)))
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_REQUEST = "request_id"
        const val EXTRA_CONSUME = "consume"
    }
}
