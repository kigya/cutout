package dev.kigya.cutout.overlay

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BubbleStarterTest {
    @Test fun `service binder calls never block Android main thread`() = runBlocking {
        val calls = mutableListOf<String>()
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun startForegroundService(intent: Intent): ComponentName {
                assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
                assertEquals(BubbleService::class.java.name, intent.component!!.className)
                calls += "start"
                return intent.component!!
            }
            override fun stopService(intent: Intent): Boolean {
                assertNotSame(Looper.getMainLooper().thread, Thread.currentThread())
                assertEquals(BubbleService::class.java.name, intent.component!!.className)
                calls += "stop"
                return true
            }
        }
        val starter = BubbleStarter(context, Dispatchers.IO)
        starter.start()
        starter.stop()
        assertEquals(listOf("start", "stop"), calls)
    }
}
