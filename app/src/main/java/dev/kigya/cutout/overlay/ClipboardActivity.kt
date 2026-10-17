package dev.kigya.cutout.overlay

import android.os.Bundle
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
    private var attempts = 0

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        overridePendingTransition(0, 0)
        val requestId = intent.getStringExtra(EXTRA_REQUEST)
        if (requestId != null && !bus.attachForeground(this, requestId)) {
            delivered = true
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        window.decorView.post { deliver() }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) deliver()
    }

    private fun deliver() {
        if (delivered || isFinishing) return
        attempts += 1
        val look = runCatching { clipboard.peek() }.getOrElse { ClipboardLook.Unreadable }
        if (look is ClipboardLook.Empty && attempts < 8 && !hasWindowFocus()) {
            window.decorView.postDelayed({ deliver() }, 200)
            return
        }
        delivered = true
        val consume = intent.getBooleanExtra(EXTRA_CONSUME, false)
        bus.offer(ClipboardRead(look, consume, intent.getStringExtra(EXTRA_REQUEST)))
        finish()
        @Suppress("DEPRECATION")
        overridePendingTransition(0, 0)
    }

    companion object {
        const val EXTRA_REQUEST = "request_id"
        const val EXTRA_CONSUME = "consume"
    }
}
