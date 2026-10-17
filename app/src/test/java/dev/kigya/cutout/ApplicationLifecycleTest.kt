package dev.kigya.cutout

import android.app.Application
import android.content.Context
import org.robolectric.RuntimeEnvironment
import android.content.pm.ApplicationInfo
import com.facebook.react.PackageList
import com.facebook.react.ReactHost
import com.facebook.react.ReactNativeApplicationEntryPoint
import com.facebook.react.defaults.DefaultReactHost
import dev.kigya.cutout.bridge.ErasePackage
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ApplicationLifecycleTest {
    @After fun tearDown() { unmockkAll() }
    @Test fun `app initializes local consent before loading React and caches one host per application`() {
        mockkStatic(ReactNativeApplicationEntryPoint::class)
        every { ReactNativeApplicationEntryPoint.loadReactNative(any()) } just Runs
        mockkConstructor(PackageList::class)
        every { anyConstructed<PackageList>().packages } answers { arrayListOf() }
        mockkStatic(DefaultReactHost::class)
        val host = mockk<ReactHost>(relaxed = true)
        val packages = mutableListOf<List<com.facebook.react.ReactPackage>>()
        val development = mutableListOf<Boolean>()
        every { DefaultReactHost.getDefaultReactHost(context = any(), packageList = capture(packages), useDevSupport = capture(development)) } returns host
        for (debug in listOf(false, true)) {
            val application = EraseApp()
            ReflectionHelpers.callInstanceMethod<Void>(application, "attachBaseContext", ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()))
            ReflectionHelpers.setField(application, "injected", true)
            application.applicationInfo.flags = if (debug) application.applicationInfo.flags or ApplicationInfo.FLAG_DEBUGGABLE else application.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE.inv()
            application.onCreate()
            assertFalse(dev.kigya.cutout.data.AppTelemetry(application).analyticsEnabled)
            assertFalse(dev.kigya.cutout.data.AppTelemetry(application).crashEnabled)
            assertSame(host, application.reactHost)
            assertSame(host, application.reactHost)
            verify(exactly = 1) { ReactNativeApplicationEntryPoint.loadReactNative(application) }
        }
        assertEquals(listOf(false, true), development)
        assertTrue(packages.all { it.single() is ErasePackage })
    }
}
