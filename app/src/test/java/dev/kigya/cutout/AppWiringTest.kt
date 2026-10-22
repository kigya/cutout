package dev.kigya.cutout

import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import androidx.core.view.WindowCompat
import com.facebook.react.ReactActivityDelegate
import com.facebook.react.ReactHost
import com.facebook.react.bridge.ReactContext
import dev.kigya.cutout.bridge.EraseEntryPoint
import dev.kigya.cutout.bridge.EraseModule
import dev.kigya.cutout.bridge.ErasePackage
import dev.kigya.cutout.data.BubbleAppearance
import dev.kigya.cutout.data.BubblePreferences
import dev.kigya.cutout.overlay.BubblePresenter
import dev.kigya.cutout.overlay.BubbleUiState
import com.facebook.react.bridge.ReactApplicationContext
import dagger.hilt.android.EntryPointAccessors
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import io.mockk.verify
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import kotlinx.coroutines.flow.MutableStateFlow

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class AppWiringTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    @After fun tearDown() { unmockkAll() }
    @Test fun `only the declared bridge module is wired from app component`() {
        val context = mockk<ReactApplicationContext>(relaxed = true)
        every { context.applicationContext } returns app
        val entry = mockk<EraseEntryPoint>(relaxed = true)
        val presenter = mockk<BubblePresenter>()
        every { presenter.state } returns MutableStateFlow(BubbleUiState())
        every { entry.presenter() } returns presenter
        every { entry.window() } returns dev.kigya.cutout.overlay.BubbleWindow()
        every { entry.mainActivityState() } returns MainActivityState()
        val preferences = mockk<BubblePreferences>()
        every { preferences.appearance } returns kotlinx.coroutines.flow.MutableStateFlow(BubbleAppearance())
        every { preferences.onboardingCompleted } returns kotlinx.coroutines.flow.MutableStateFlow(false)
        every { preferences.enabled } returns MutableStateFlow(false)
        every { entry.preferences() } returns preferences
        mockkStatic(EntryPointAccessors::class)
        every { EntryPointAccessors.fromApplication(app, EraseEntryPoint::class.java) } returns entry
        val packageInfo = ErasePackage()
        assertNull(packageInfo.getModule("unrelated", context))
        verify(exactly = 0) { EntryPointAccessors.fromApplication(any(), EraseEntryPoint::class.java) }
        val module = packageInfo.getModule(EraseModule.NAME, context)
        assertTrue(module is EraseModule)
        val info = packageInfo.getReactModuleInfoProvider().getReactModuleInfos().getValue(EraseModule.NAME)
        assertEquals(EraseModule::class.java.name, info.className)
        assertFalse(info.needsEagerInit)
        assertFalse(info.isTurboModule)
        (module as EraseModule).invalidate()
    }
    @Test fun `activity system bars follow explicit theme and then the system night setting`() {
        for ((theme, night, light) in listOf(Triple("light", true, true), Triple("dark", false, false), Triple("system", true, false), Triple("system", false, true))) {
            app.getSharedPreferences("cutout_preferences", Context.MODE_PRIVATE).edit().putString("theme", theme).commit()
            val controller = Robolectric.buildActivity(MainActivity::class.java)
            val activity = controller.get()
            ReflectionHelpers.setField(activity, "mApplication", mockk<EraseApp>(relaxed = true))
            val delegate = mockk<ReactActivityDelegate>(relaxed = true)
            ReflectionHelpers.setField(activity, "mDelegate", delegate)
            val config = Configuration(activity.resources.configuration).apply {
                uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                    if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            }
            activity.resources.updateConfiguration(config, activity.resources.displayMetrics)
            controller.create()
            val bars = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
            assertEquals(light, bars.isAppearanceLightStatusBars)
            assertEquals(light, bars.isAppearanceLightNavigationBars)
            activity.onConfigurationChanged(config)
            assertEquals(light, bars.isAppearanceLightStatusBars)
            controller.destroy()
        }
    }
    @Test fun `new editor intent reaches a live React context once while ordinary intent does not`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        ReflectionHelpers.setField(activity, "mDelegate", mockk<ReactActivityDelegate>(relaxed = true))
        val application = mockk<EraseApp>(relaxed = true)
        val host = mockk<ReactHost>(relaxed = true)
        val context = mockk<ReactContext>(relaxed = true)
        every { application.reactHost } returns host
        every { host.currentReactContext } returns context
        ReflectionHelpers.setField(activity, "mApplication", application)
        controller.create()
        ReflectionHelpers.callInstanceMethod<Void>(activity, "emitEdit", ReflectionHelpers.ClassParameter.from<Intent>(Intent::class.java, null))
        activity.onNewIntent(Intent())
        verify(exactly = 0) { context.emitDeviceEvent(any()) }
        val editor = Intent().putExtra(MainActivity.EXTRA_EDIT, true)
        activity.onNewIntent(editor)
        assertSame(editor, activity.intent)
        verify(exactly = 1) { context.emitDeviceEvent("eraseOpenEdit") }
        every { host.currentReactContext } returns null
        activity.onNewIntent(editor)
        verify(exactly = 1) { context.emitDeviceEvent("eraseOpenEdit") }
        controller.destroy()
    }
    @Test fun `activity lifecycle publishes foreground boundaries and coordinates overlay host`() {
        val controller = Robolectric.buildActivity(MainActivity::class.java)
        val activity = controller.get()
        val application = mockk<EraseApp>(relaxed = true)
        val state = MainActivityState()
        val lifecycle = mockk<dev.kigya.cutout.overlay.BubbleReactLifecycle>(relaxed = true)
        every { application.mainActivityState } returns state
        every { application.bubbleLifecycle } returns lifecycle
        ReflectionHelpers.setField(activity, "mApplication", application)
        ReflectionHelpers.setField(activity, "mDelegate", mockk<ReactActivityDelegate>(relaxed = true))
        controller.create().start()
        assertTrue(state.state.value.visible)
        assertFalse(state.state.value.resumed)
        controller.resume()
        assertTrue(state.state.value.resumed)
        verify(exactly = 1) { lifecycle.beforeActivityResume() }
        controller.pause()
        assertFalse(state.state.value.resumed)
        assertTrue(state.state.value.visible)
        controller.stop()
        assertFalse(state.state.value.visible)
        controller.destroy()
        verify(exactly = 2) { lifecycle.afterActivityCallback() }
    }
}
