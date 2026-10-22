package dev.kigya.cutout.overlay

import android.app.Application
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import dev.kigya.cutout.data.BubblePreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.flow.flowOf
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSettings
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BootReceiverTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private fun receiver(enabled: Boolean): BootReceiver = BootReceiver().apply {
        ReflectionHelpers.setField(this, "injected", true)
        preferences = mockk { every { this@mockk.enabled } returns flowOf(enabled) }
    }
    @Test fun `unrelated broadcasts and revoked overlay permission cannot restart Bubble`() {
        val receiver = receiver(true)
        ShadowSettings.setCanDrawOverlays(true)
        receiver.onReceive(app, Intent("unrelated"))
        receiver.onReceive(app, Intent())
        ShadowSettings.setCanDrawOverlays(false)
        receiver.onReceive(app, Intent(Intent.ACTION_BOOT_COMPLETED))
        verify(exactly = 0) { receiver.preferences.enabled }
    }
    @Test fun `boot and upgrade respect opt in and always release async broadcast even when OS rejects service`() {
        for (enabled in listOf(false, true)) for (action in listOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED)) {
            val receiver = receiver(enabled)
            val completion = mockk<BroadcastReceiver.PendingResult>(relaxed = true)
            ReflectionHelpers.setField(receiver, "mPendingResult", completion)
            val starts = java.util.concurrent.atomic.AtomicInteger()
            val context = object : ContextWrapper(app) {
                override fun getApplicationContext(): Context = this
                override fun startForegroundService(intent: Intent): ComponentName? {
                    assertEquals(BubbleService::class.java.name, intent.component!!.className)
                    starts.incrementAndGet()
                    throw IllegalStateException("background start disallowed")
                }
            }
            ShadowSettings.setCanDrawOverlays(true)
            receiver.onReceive(context, Intent(action))
            verify(timeout = 3000, exactly = 1) { completion.finish() }
            assertEquals(if (enabled) 1 else 0, starts.get())
        }
    }
}
