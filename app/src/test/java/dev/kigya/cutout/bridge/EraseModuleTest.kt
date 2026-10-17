package dev.kigya.cutout.bridge

import android.app.Application
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.os.Looper
import com.facebook.react.bridge.Arguments
import com.facebook.react.bridge.CatalystInstance
import com.facebook.react.bridge.Callback
import com.facebook.react.bridge.JavaOnlyArray
import com.facebook.react.bridge.JavaOnlyMap
import com.facebook.react.bridge.JavaScriptContextHolder
import com.facebook.react.bridge.JavaScriptModule
import com.facebook.react.bridge.NativeModule
import com.facebook.react.bridge.Promise
import com.facebook.react.bridge.ReadableMap
import com.facebook.react.bridge.ReactApplicationContext
import com.facebook.react.bridge.RuntimeExecutor
import com.facebook.react.bridge.UIManager
import com.facebook.react.bridge.UiThreadUtil
import com.facebook.react.bridge.WritableMap
import com.facebook.react.turbomodule.core.interfaces.CallInvokerHolder
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import dev.kigya.cutout.cutout.CutOutcome
import dev.kigya.cutout.cutout.ImageCutter
import dev.kigya.cutout.cutout.MaskEditor
import dev.kigya.cutout.cutout.MaskWrite
import dev.kigya.cutout.data.BubblePreferences
import dev.kigya.cutout.data.ClipboardImages
import dev.kigya.cutout.data.ClipboardLook
import dev.kigya.cutout.data.EditFiles
import dev.kigya.cutout.data.EditSessions
import dev.kigya.cutout.data.GalleryStore
import dev.kigya.cutout.data.IncomingImage
import dev.kigya.cutout.domain.Messages
import dev.kigya.cutout.domain.OemFamily
import dev.kigya.cutout.domain.PermissionKind
import dev.kigya.cutout.overlay.BubblePhase
import dev.kigya.cutout.overlay.BubblePresenter
import dev.kigya.cutout.overlay.BubbleStarter
import dev.kigya.cutout.overlay.BubbleUiEvent
import dev.kigya.cutout.overlay.BubbleUiState
import dev.kigya.cutout.overlay.BubbleWindow
import dev.kigya.cutout.overlay.ClipboardBus
import dev.kigya.cutout.overlay.ClipboardRead
import dev.kigya.cutout.overlay.PickBus
import dev.kigya.cutout.permissions.GrantStatus
import dev.kigya.cutout.permissions.PermissionCenter
import dev.kigya.cutout.permissions.PermissionSnapshot
import dev.kigya.cutout.permissions.SettingsNavigator
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.mockkStatic
import io.mockk.unmockkObject
import io.mockk.unmockkStatic
import io.mockk.verify
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.TestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@Suppress("DEPRECATION")
class EraseModuleTest {
    private lateinit var dispatcher: TestDispatcher
    private val modules = CopyOnWriteArrayList<EraseModule>()

    @Before
    fun setUp() {
        dispatcher = UnconfinedTestDispatcher()
        Dispatchers.setMain(dispatcher)
        mockkStatic(Arguments::class)
        every { Arguments.createMap() } answers { JavaOnlyMap() }
        every { Arguments.createArray() } answers { JavaOnlyArray() }
        mockkObject(UiThreadUtil)
        every { UiThreadUtil.runOnUiThread(any()) } answers {
            firstArg<Runnable>().run()
            true
        }
    }

    @After
    fun tearDown() {
        modules.forEach(EraseModule::invalidate)
        modules.clear()
        unmockkObject(UiThreadUtil)
        unmockkStatic(Arguments::class)
        Dispatchers.resetMain()
    }

    private class TestReactContext(base: Context) : ReactApplicationContext(base) {
        val startedActivities = mutableListOf<Intent>()
        val deviceEvents = CopyOnWriteArrayList<Pair<String, Any?>>()
        var activeReactInstance = false
        var activityFailure: Exception? = null
        var foregroundActivity: Activity? = null

        override fun <T : JavaScriptModule> getJSModule(jsInterface: Class<T>): T? = null
        override fun <T : NativeModule> hasNativeModule(nativeModuleInterface: Class<T>): Boolean = false
        override fun getNativeModules(): Collection<NativeModule> = emptyList()
        override fun <T : NativeModule> getNativeModule(nativeModuleInterface: Class<T>): T? = null
        override fun getNativeModule(moduleName: String): NativeModule? = null
        override fun getCatalystInstance(): CatalystInstance? = null
        override fun hasActiveCatalystInstance(): Boolean = false
        override fun hasActiveReactInstance(): Boolean = activeReactInstance
        override fun hasCatalystInstance(): Boolean = false
        override fun hasReactInstance(): Boolean = false
        override fun getRuntimeExecutor(): RuntimeExecutor? = null
        override fun destroy() = Unit
        override fun handleException(p0: Exception) = Unit
        override fun isBridgeless(): Boolean = false
        override fun getJavaScriptContextHolder(): JavaScriptContextHolder? = null
        override fun getJSCallInvokerHolder(): CallInvokerHolder? = null
        override fun getFabricUIManager(): UIManager? = null
        override fun getSourceURL(): String? = null
        override fun emitDeviceEvent(eventName: String, args: Any?) {
            deviceEvents += eventName to args
        }
        override fun getCurrentActivity(): Activity? = foregroundActivity
        override fun registerSegment(p0: Int, p1: String, p2: Callback) = Unit
        override fun startActivity(intent: Intent) {
            activityFailure?.let { throw it }
            startedActivities += intent
        }
    }

    private class PromiseResult : Promise {
        val resolved = CompletableDeferred<Any?>()
        val rejected = CompletableDeferred<Pair<String?, String?>>()

        override fun resolve(value: Any?) { resolved.complete(value) }
        override fun reject(code: String?, message: String?) { rejected.complete(code to message) }
        override fun reject(code: String?, throwable: Throwable?) { rejected.complete(code to throwable?.message) }
        override fun reject(code: String?, message: String?, throwable: Throwable?) { rejected.complete(code to (message ?: throwable?.message)) }
        override fun reject(throwable: Throwable) { rejected.complete(null to throwable.message) }
        override fun reject(throwable: Throwable, userInfo: WritableMap) { rejected.complete(null to throwable.message) }
        override fun reject(code: String?, userInfo: WritableMap) { rejected.complete(code to null) }
        override fun reject(code: String?, throwable: Throwable?, userInfo: WritableMap) { rejected.complete(code to throwable?.message) }
        override fun reject(code: String?, message: String?, userInfo: WritableMap) { rejected.complete(code to message) }
        override fun reject(code: String?, message: String?, throwable: Throwable?, userInfo: WritableMap?) { rejected.complete(code to (message ?: throwable?.message)) }
        @Suppress("DEPRECATION")
        override fun reject(message: String) { rejected.complete(null to message) }
    }

    private data class Fixture(
        val module: EraseModule,
        val context: TestReactContext,
        val permissions: PermissionCenter,
        val preferences: BubblePreferences,
        val navigator: SettingsNavigator,
        val bubble: BubbleStarter,
        val presenter: BubblePresenter,
        val state: MutableStateFlow<BubbleUiState>,
        val cutter: ImageCutter,
        val sessions: EditSessions,
        val picks: PickBus,
        val clipboard: ClipboardBus,
        val masks: MaskEditor,
        val window: BubbleWindow,
        val gallery: GalleryStore,
        val images: ClipboardImages,
    )

    private fun fixture(): Fixture {
        val context = TestReactContext(RuntimeEnvironment.getApplication())
        context.getSharedPreferences("cutout_permission_history", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val permissions = mockk<PermissionCenter>(relaxed = true)
        val preferences = mockk<BubblePreferences>()
        val navigator = mockk<SettingsNavigator>(relaxed = true)
        every { navigator.open(any()) } returns true
        val bubble = mockk<BubbleStarter>(relaxed = true)
        val presenter = mockk<BubblePresenter>()
        val state = MutableStateFlow(BubbleUiState())
        every { presenter.state } returns state
        coEvery { presenter.onEvent(any()) } answers {
            state.value = state.value.copy(revision = state.value.revision + 1, expanded = true)
        }
        val cutter = mockk<ImageCutter>()
        coEvery { cutter.prepare() } returns Result.success(Unit)
        val sessions = mockk<EditSessions>(relaxed = true)
        every { sessions.pending } returns null
        val picks = PickBus()
        val clipboard = ClipboardBus()
        val masks = mockk<MaskEditor>()
        val window = BubbleWindow()
        val gallery = mockk<GalleryStore>()
        val images = mockk<ClipboardImages>()
        every { preferences.enabled } returns MutableStateFlow(false)
        every { images.hasPicture() } returns true
        val module = EraseModule(
            context, permissions, preferences, navigator, bubble, presenter, cutter,
            sessions, picks, clipboard, masks, window, gallery, images,
        )
        modules += module
        return Fixture(module, context, permissions, preferences, navigator, bubble, presenter,
            state, cutter, sessions, picks, clipboard, masks, window, gallery, images)
    }

    private suspend fun PromiseResult.value() = resolved.await()
    private fun ReadableMap.string(key: String) = getString(key)

    @Test
    fun `home snapshots permissions off main thread and returns bridge allowlist`() = runTest {
        val f = fixture()
        val kinds = PermissionKind.entries
        every { f.permissions.family() } returns OemFamily.Xiaomi
        var snapshotNumber = 0
        every { f.permissions.snapshot() } answers {
            assertNotSame("permission probes must not block the UI thread", Looper.getMainLooper().thread, Thread.currentThread())
            snapshotNumber += 1
            kinds.mapIndexed { index, kind ->
                val status = when {
                    index == 0 && snapshotNumber > 1 -> GrantStatus.Granted
                    index == 1 -> GrantStatus.Unknown
                    else -> GrantStatus.Denied
                }
                PermissionSnapshot(kind, status)
            }
        }
        every { f.images.hasPicture() } throws IllegalStateException("clipboard unavailable")

        val promise = PromiseResult()
        f.module.getHome(promise)
        val home = promise.value() as WritableMap
        assertTrue(home.getBoolean("bubbleSupported"))
        assertFalse(home.getBoolean("bubbleEnabled"))
        assertTrue(home.getBoolean("showXiaomiTip"))
        assertFalse(home.getBoolean("clipboardReady"))
        val rows = home.getArray("rows")!!
        assertEquals(PermissionKind.entries.size, rows.size())
        val first = rows.getMap(0)!!
        assertEquals("overlay", first.string("kind"))
        assertEquals("denied", first.string("status"))
        assertEquals("optional", first.string("need"))
        assertEquals(0, first.getInt("deniedCount"))
        assertFalse(first.getBoolean("permanentlyDenied"))
        assertFalse(first.string("title").isNullOrBlank())

        every { f.permissions.family() } returns OemFamily.Generic
        val refreshed = PromiseResult()
        f.module.getHome(refreshed)
        val secondHome = refreshed.value() as WritableMap
        assertFalse(secondHome.getBoolean("showXiaomiTip"))
        assertEquals("granted", secondHome.getArray("rows")!!.getMap(0)!!.string("status"))
        assertEquals("unknown", secondHome.getArray("rows")!!.getMap(1)!!.string("status"))
    }

    @Test
    fun `home synchronizes an enabled overlay and reports a readable clipboard`() = runTest {
        val f = fixture()
        every { f.preferences.enabled } returns MutableStateFlow(true)
        every { f.permissions.snapshot() } returns listOf(PermissionSnapshot(PermissionKind.Overlay, GrantStatus.Granted))
        every { f.permissions.family() } returns OemFamily.Generic
        every { f.images.hasPicture() } returns true
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        try {
            val promise = PromiseResult()
            f.module.getHome(promise)
            val home = promise.value() as WritableMap

            assertTrue(home.getBoolean("bubbleEnabled"))
            assertTrue(home.getBoolean("clipboardReady"))
            val overlay = home.getArray("rows")!!.getMap(0)!!
            assertEquals("requiredForBubble", overlay.string("need"))
            coVerify(exactly = 1) { f.bubble.start() }
            coVerify(exactly = 0) { f.bubble.stop() }
        } finally {
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun `home rejects when permission snapshot cannot be read`() = runTest {
        val f = fixture()
        every { f.preferences.enabled } returns MutableStateFlow(false)
        every { f.permissions.snapshot() } throws IllegalStateException("permission service unavailable")

        val promise = PromiseResult()
        f.module.getHome(promise)

        assertEquals("home" to "Could not refresh permissions", promise.rejected.await())
        coVerify(exactly = 1) { f.bubble.stop() }
    }

    @Test
    fun `home invalidation rejects while waiting for persisted bubble state`() = runTest {
        val f = fixture()
        val waiting = CompletableDeferred<Unit>()
        every { f.preferences.enabled } returns flow {
            waiting.complete(Unit)
            awaitCancellation()
        }

        val promise = PromiseResult()
        f.module.getHome(promise)
        waiting.await()
        f.module.invalidate()

        assertEquals("cancelled" to "Home refresh cancelled", promise.rejected.await())
    }

    @Test
    fun `home service cancellation rejects as cancelled before probing permissions`() = runTest {
        val f = fixture()
        every { f.preferences.enabled } returns MutableStateFlow(true)
        every { f.permissions.family() } returns OemFamily.Generic
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        coEvery { f.bubble.start() } throws kotlinx.coroutines.CancellationException("service start cancelled")
        try {
            val promise = PromiseResult()
            f.module.getHome(promise)

            assertEquals("cancelled" to "Home refresh cancelled", promise.rejected.await())
            verify(exactly = 0) { f.permissions.snapshot() }
        } finally {
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun `home revocation disables a stale enabled preference before returning`() = runTest {
        val f = fixture()
        every { f.preferences.enabled } returns MutableStateFlow(true)
        every { f.permissions.snapshot() } returns emptyList()
        every { f.permissions.family() } returns OemFamily.Generic
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        coEvery { f.preferences.setEnabled(false) } returns Unit

        val promise = PromiseResult()
        f.module.getHome(promise)
        val home = promise.value() as WritableMap

        assertFalse(home.getBoolean("bubbleEnabled"))
        coVerify(exactly = 1) { f.preferences.setEnabled(false) }
        coVerify(exactly = 1) { f.bubble.stop() }
    }

    @Test
    fun `home service start failure recovers to a resolved disabled snapshot`() = runTest {
        val f = fixture()
        every { f.preferences.enabled } returns MutableStateFlow(true)
        every { f.permissions.snapshot() } returns emptyList()
        every { f.permissions.family() } returns OemFamily.Generic
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        coEvery { f.bubble.start() } throws IllegalStateException("service unavailable")
        coEvery { f.preferences.setEnabled(false) } returns Unit
        try {
            val promise = PromiseResult()
            f.module.getHome(promise)
            val home = promise.value() as WritableMap

            assertFalse(home.getBoolean("bubbleEnabled"))
            coVerify(exactly = 1) { f.preferences.setEnabled(false) }
            coVerify(exactly = 1) { f.bubble.stop() }
        } finally {
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun `home service start failure still settles when best effort cleanup also fails`() = runTest {
        val f = fixture()
        every { f.preferences.enabled } returns MutableStateFlow(true)
        every { f.permissions.snapshot() } returns emptyList()
        every { f.permissions.family() } returns OemFamily.Generic
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        coEvery { f.bubble.start() } throws IllegalStateException("service unavailable")
        coEvery { f.preferences.setEnabled(false) } throws IllegalStateException("storage unavailable")
        coEvery { f.bubble.stop() } throws IllegalStateException("service unavailable")
        try {
            val promise = PromiseResult()
            f.module.getHome(promise)
            val home = promise.value() as WritableMap

            assertFalse(home.getBoolean("bubbleEnabled"))
            assertTrue(home.hasKey("rows"))
            coVerify(exactly = 1) { f.preferences.setEnabled(false) }
            coVerify(exactly = 1) { f.bubble.stop() }
        } finally {
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun `bubble service failure rejects and best-effort disables persisted state`() = runTest {
        val f = fixture()
        every { f.permissions.snapshot() } returns listOf(
            PermissionSnapshot(PermissionKind.Overlay, GrantStatus.Granted),
        )
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        coEvery { f.preferences.setEnabled(any()) } returns Unit
        coEvery { f.bubble.start() } throws IllegalStateException("service unavailable")
        try {
            val promise = PromiseResult()
            f.module.setBubble(true, promise)

            assertEquals("bubble" to "Could not change Bubble", promise.rejected.await())
            coVerify(exactly = 1) { f.preferences.setEnabled(true) }
            coVerify(exactly = 1) { f.preferences.setEnabled(false) }
            coVerify(exactly = 1) { f.bubble.stop() }
        } finally {
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun `bubble service failure settles even when best effort recovery fails`() = runTest {
        val f = fixture()
        every { f.permissions.snapshot() } returns listOf(
            PermissionSnapshot(PermissionKind.Overlay, GrantStatus.Granted),
        )
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        coEvery { f.preferences.setEnabled(true) } returns Unit
        coEvery { f.preferences.setEnabled(false) } throws IllegalStateException("storage unavailable")
        coEvery { f.bubble.start() } throws IllegalStateException("service unavailable")
        coEvery { f.bubble.stop() } throws IllegalStateException("service unavailable")
        try {
            val promise = PromiseResult()
            f.module.setBubble(true, promise)

            assertEquals("bubble" to "Could not change Bubble", promise.rejected.await())
            coVerify(exactly = 1) { f.preferences.setEnabled(true) }
            coVerify(exactly = 1) { f.preferences.setEnabled(false) }
            coVerify(exactly = 1) { f.bubble.stop() }
        } finally {
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun `bubble service cancellation rejects with the cancellation contract`() = runTest {
        val f = fixture()
        every { f.permissions.snapshot() } returns listOf(
            PermissionSnapshot(PermissionKind.Overlay, GrantStatus.Granted),
        )
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        coEvery { f.preferences.setEnabled(true) } returns Unit
        val waiting = CompletableDeferred<Unit>()
        coEvery { f.bubble.start() } coAnswers {
            waiting.complete(Unit)
            awaitCancellation()
        }
        try {
            val promise = PromiseResult()
            f.module.setBubble(true, promise)
            waiting.await()
            f.module.invalidate()

            assertEquals("cancelled" to "Bubble change cancelled", promise.rejected.await())
        } finally {
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun `bubble start cancellation from the service rejects with the cancellation contract`() = runTest {
        val f = fixture()
        every { f.permissions.snapshot() } returns listOf(
            PermissionSnapshot(PermissionKind.Overlay, GrantStatus.Granted),
        )
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        coEvery { f.preferences.setEnabled(true) } returns Unit
        coEvery { f.bubble.start() } throws kotlinx.coroutines.CancellationException("start cancelled")
        try {
            val promise = PromiseResult()
            f.module.setBubble(true, promise)

            assertEquals("cancelled" to "Bubble change cancelled", promise.rejected.await())
            coVerify(exactly = 1) { f.preferences.setEnabled(true) }
        } finally {
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun `hide service cancellation rejects with the cancellation contract`() = runTest {
        val f = fixture()
        coEvery { f.preferences.setEnabled(false) } returns Unit
        val waiting = CompletableDeferred<Unit>()
        coEvery { f.bubble.stop() } coAnswers {
            waiting.complete(Unit)
            awaitCancellation()
        }

        val promise = PromiseResult()
        f.module.hideBubble(promise)
        waiting.await()
        f.module.invalidate()

        assertEquals("cancelled" to "Bubble change cancelled", promise.rejected.await())
    }

    @Test
    fun `hide cancellation from the service rejects with the cancellation contract`() = runTest {
        val f = fixture()
        coEvery { f.preferences.setEnabled(false) } returns Unit
        coEvery { f.bubble.stop() } throws kotlinx.coroutines.CancellationException("stop cancelled")

        val promise = PromiseResult()
        f.module.hideBubble(promise)

        assertEquals("cancelled" to "Bubble change cancelled", promise.rejected.await())
        coVerify(exactly = 1) { f.preferences.setEnabled(false) }
    }

    @Test
    fun `bubble recovery still settles as failed when persistence cleanup is cancelled`() = runTest {
        val f = fixture()
        every { f.permissions.snapshot() } returns listOf(
            PermissionSnapshot(PermissionKind.Overlay, GrantStatus.Granted),
        )
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        coEvery { f.preferences.setEnabled(true) } returns Unit
        coEvery { f.preferences.setEnabled(false) } throws kotlinx.coroutines.CancellationException("recovery cancelled")
        coEvery { f.bubble.start() } throws IllegalStateException("start failed")
        try {
            val promise = PromiseResult()
            f.module.setBubble(true, promise)

            // The operation already failed before cleanup was cancelled; the bridge settles
            // that original failure once while allowing the cleanup cancellation to propagate.
            assertEquals("bubble" to "Could not change Bubble", promise.rejected.await())
            coVerify(exactly = 0) { f.bubble.stop() }
        } finally {
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun `bubble recovery still settles as failed when stopping cleanup is cancelled`() = runTest {
        val f = fixture()
        every { f.permissions.snapshot() } returns listOf(
            PermissionSnapshot(PermissionKind.Overlay, GrantStatus.Granted),
        )
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        coEvery { f.preferences.setEnabled(true) } returns Unit
        coEvery { f.preferences.setEnabled(false) } throws IllegalStateException("storage failed")
        coEvery { f.bubble.start() } throws IllegalStateException("start failed")
        coEvery { f.bubble.stop() } throws kotlinx.coroutines.CancellationException("recovery stop cancelled")
        try {
            val promise = PromiseResult()
            f.module.setBubble(true, promise)

            assertEquals("bubble" to "Could not change Bubble", promise.rejected.await())
            coVerify(exactly = 1) { f.preferences.setEnabled(false) }
            coVerify(exactly = 1) { f.bubble.stop() }
        } finally {
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false)
        }
    }

    @Test
    fun `hide service failure rejects without leaving persisted bubble enabled`() = runTest {
        val f = fixture()
        coEvery { f.preferences.setEnabled(false) } returns Unit
        coEvery { f.bubble.stop() } throws IllegalStateException("service unavailable")

        val promise = PromiseResult()
        f.module.hideBubble(promise)

        assertEquals("bubble" to "Could not change Bubble", promise.rejected.await())
        coVerify(exactly = 2) { f.preferences.setEnabled(false) }
        coVerify(exactly = 2) { f.bubble.stop() }
    }

    @Test
    fun `bridge rejects unknown permission and opens recognized one`() = runTest {
        val f = fixture()
        val invalid = PromiseResult()
        f.module.openPermission("overlay-ish", invalid)
        assertEquals("kind" to "overlay-ish", invalid.rejected.await())

        val valid = PromiseResult()
        f.module.openPermission("overlay", valid)
        assertNull(valid.value())
        verify(exactly = 1) { f.navigator.open(PermissionKind.Overlay) }
        PermissionKind.entries.drop(1).forEach { kind ->
            val permissionName = when (kind) {
                PermissionKind.Overlay -> error("already checked")
                PermissionKind.BackgroundPopup -> "backgroundPopup"
                PermissionKind.Autostart -> "autostart"
                PermissionKind.Battery -> "battery"
                PermissionKind.Notifications -> "notifications"
            }
            f.module.openPermission(permissionName, PromiseResult())
        }
        PermissionKind.entries.forEach { kind -> verify(exactly = 1) { f.navigator.open(kind) } }
    }

    @Test
    fun `bubble enable respects overlay grant and hide always stops service`() = runTest {
        val f = fixture()
        coEvery { f.preferences.setEnabled(any()) } returns Unit
        every { f.permissions.snapshot() } returns listOf(
            PermissionSnapshot(PermissionKind.Overlay, GrantStatus.Denied),
        )
        val denied = PromiseResult()
        f.module.setBubble(true, denied)
        assertNull(denied.value())
        coVerify(exactly = 1) { f.preferences.setEnabled(false) }
        coVerify(exactly = 1) { f.bubble.stop() }

        val disabled = PromiseResult()
        f.module.setBubble(false, disabled)
        assertNull(disabled.value())
        coVerify(exactly = 1) { f.permissions.snapshot() }
        coVerify(exactly = 2) { f.preferences.setEnabled(false) }

        every { f.permissions.snapshot() } returns listOf(
            PermissionSnapshot(PermissionKind.Overlay, GrantStatus.Granted),
        )
        val granted = PromiseResult()
        f.module.setBubble(true, granted)
        assertNull(granted.value())
        coVerify(exactly = 2) { f.permissions.snapshot() }
        coVerify(exactly = 1) { f.preferences.setEnabled(true) }

        val hidden = PromiseResult()
        f.module.hideBubble(hidden)
        assertNull(hidden.value())
        coVerify(exactly = 3) { f.preferences.setEnabled(false) }
        coVerify(exactly = 4) { f.bubble.stop() }
    }

    @Test
    fun `bubble requires an explicitly granted overlay permission and starts when the OS allows it`() = runTest {
        val f = fixture()
        coEvery { f.preferences.setEnabled(any()) } returns Unit
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true)
        every { f.permissions.snapshot() } returns listOf(
            PermissionSnapshot(PermissionKind.Overlay, GrantStatus.Unknown),
            PermissionSnapshot(PermissionKind.Notifications, GrantStatus.Granted),
        )
        val unknown = PromiseResult()
        f.module.setBubble(true, unknown)
        assertNull(unknown.value())
        coVerify(exactly = 1) { f.preferences.setEnabled(false) }
        coVerify(exactly = 1) { f.bubble.stop() }
        coVerify(exactly = 0) { f.bubble.start() }

        every { f.permissions.snapshot() } returns listOf(
            PermissionSnapshot(PermissionKind.Overlay, GrantStatus.Granted),
        )
        val granted = PromiseResult()
        f.module.setBubble(true, granted)
        assertNull(granted.value())
        coVerify(exactly = 1) { f.preferences.setEnabled(true) }
        coVerify(exactly = 1) { f.bubble.start() }
    }

    @Test
    fun `picker selection is correlated and raster outcome is mapped for JS`() = runTest {
        val f = fixture()
        val expected = IncomingImage.Svg("<svg/>")
        coEvery { f.cutter.cut(expected, any()) } returns CutOutcome.CopiedRaster(
            message = Messages.SVG_AS_PNG,
            previewPath = "/cache/preview.png",
            edit = EditFiles("/cache/original.png", "/cache/cutout.png", 11, 7),
            rasterizedSvg = true,
        )
        val promise = PromiseResult()
        f.module.pickAndCut("file", promise)
        runCurrent()
        val requestId = requireNotNull(f.picks.editorRequestId)
        val intent = f.context.startedActivities.single()
        assertEquals(PickBus.TARGET_EDITOR, intent.getStringExtra("target"))
        assertEquals(requestId, intent.getStringExtra("request_id"))
        f.picks.offer(expected, PickBus.TARGET_EDITOR, requestId)

        val result = promise.value() as WritableMap
        assertEquals("ready", result.string("status"))
        assertEquals(Messages.SVG_AS_PNG, result.string("message"))
        assertEquals("/cache/original.png", result.string("originalPath"))
        assertEquals(11, result.getInt("width"))
        assertEquals(7, result.getInt("height"))
        coVerify(exactly = 1) { f.cutter.cut(expected, any()) }
    }

    @Test
    fun `canceling an outstanding picker resolves once and clears correlated request`() = runTest {
        val f = fixture()
        val promise = PromiseResult()
        f.module.pickAndCut("gallery", promise)
        runCurrent()
        val requestId = requireNotNull(f.picks.editorRequestId)
        f.module.cancelCut(PromiseResult())
        runCurrent()

        val result = promise.value() as WritableMap
        assertEquals("failed", result.string("status"))
        assertEquals(Messages.CANCELLED, result.string("message"))
        assertNull(f.picks.editorRequestId)
        coVerify(exactly = 0) { f.cutter.cut(any(), any()) }
        assertTrue(requestId.isNotBlank())
    }

    @Test
    fun `starting a new picker cancels the previous request and owns the next result`() = runTest {
        val f = fixture()
        val first = PromiseResult()
        f.module.pickAndCut("file", first)
        runCurrent()
        val firstId = requireNotNull(f.picks.editorRequestId)

        val second = PromiseResult()
        f.module.pickAndCut("gallery", second)
        runCurrent()
        val secondId = requireNotNull(f.picks.editorRequestId)
        assertTrue(firstId != secondId)
        val cancelled = first.value() as WritableMap
        assertEquals("failed", cancelled.string("status"))
        assertEquals(Messages.CANCELLED, cancelled.string("message"))

        val image = IncomingImage.Svg("<svg/>")
        coEvery { f.cutter.cut(image, any()) } returns CutOutcome.CopiedSvg(Messages.COPIED)
        f.picks.offer(image, PickBus.TARGET_EDITOR, secondId)
        val copied = second.value() as WritableMap
        assertEquals("svg", copied.string("status"))
        coVerify(exactly = 1) { f.cutter.cut(image, any()) }
    }

    @Test
    fun `fixture bridge maps manual and failed cut outcomes`() = runTest {
        val f = fixture()
        val edit = EditFiles("/cache/source.png", "/cache/manual.png", 8, 6)
        coEvery { f.cutter.cutFixture(any()) } returns CutOutcome.Manual(Messages.MANUAL, edit)
        val manualPromise = PromiseResult()
        f.module.cutFixture(manualPromise)
        val manual = manualPromise.value() as WritableMap
        assertEquals("manual", manual.string("status"))
        assertEquals(Messages.MANUAL, manual.string("message"))
        assertEquals(8, manual.getInt("width"))
        assertEquals(6, manual.getInt("height"))

        coEvery { f.cutter.cutFixture(any()) } returns CutOutcome.Failed("fixture unavailable")
        val failedPromise = PromiseResult()
        f.module.cutFixture(failedPromise)
        val failed = failedPromise.value() as WritableMap
        assertEquals("failed", failed.string("status"))
        assertEquals("fixture unavailable", failed.string("message"))

        coEvery { f.cutter.cutFixture(any()) } throws IllegalStateException("fixture threw")
        val thrownPromise = PromiseResult()
        f.module.cutFixture(thrownPromise)
        val thrown = thrownPromise.value() as WritableMap
        assertEquals("failed", thrown.string("status"))
        assertEquals(Messages.READ_FAILED, thrown.string("message"))
    }

    @Test
    fun `picker launch failure settles as cancelled and clears the request`() = runTest {
        val f = fixture()
        f.context.activityFailure = IllegalStateException("picker unavailable")
        val promise = PromiseResult()
        f.module.pickAndCut("file", promise)
        runCurrent()

        val result = promise.value() as WritableMap
        assertEquals("failed", result.string("status"))
        assertEquals(Messages.CANCELLED, result.string("message"))
        assertNull(f.picks.editorRequestId)
        coVerify(exactly = 0) { f.cutter.cut(any(), any()) }
    }

    @Test
    fun `clipboard launch failure reports empty clipboard and success maps svg copy`() = runTest {
        val f = fixture()
        f.context.activityFailure = IllegalStateException("clipboard activity unavailable")
        val failedPromise = PromiseResult()
        f.module.cutClipboard(failedPromise)
        runCurrent()
        val failed = failedPromise.value() as WritableMap
        assertEquals("failed", failed.string("status"))
        assertEquals(Messages.EMPTY_CLIPBOARD, failed.string("message"))
        assertNull(f.clipboard.consumeRequestId)

        f.context.activityFailure = null
        val image = IncomingImage.Svg("<svg/>")
        coEvery { f.cutter.cut(image, any()) } returns CutOutcome.CopiedSvg(Messages.COPIED)
        val copiedPromise = PromiseResult()
        f.module.cutClipboard(copiedPromise)
        runCurrent()
        val requestId = requireNotNull(f.clipboard.consumeRequestId)
        f.clipboard.offer(ClipboardRead(ClipboardLook.Found(image), consume = true, requestId = requestId))
        val copied = copiedPromise.value() as WritableMap
        assertEquals("svg", copied.string("status"))
        assertEquals(Messages.COPIED, copied.string("message"))
        coVerify(exactly = 1) { f.cutter.cut(image, any()) }
    }

    @Test
    fun `clipboard timeout clears the consume reservation without running inference`() = runTest {
        val f = fixture()
        val promise = PromiseResult()
        f.module.cutClipboard(promise)
        runCurrent()
        val requestId = requireNotNull(f.clipboard.consumeRequestId)

        advanceTimeBy(20_001)
        runCurrent()

        val result = promise.value() as WritableMap
        assertEquals("failed", result.string("status"))
        assertEquals(Messages.EMPTY_CLIPBOARD, result.string("message"))
        assertNull(f.clipboard.consumeRequestId)
        coVerify(exactly = 0) { f.cutter.cut(any(), any()) }
        assertTrue(requestId.isNotBlank())
    }

    @Test
    fun `picker timeout clears the editor reservation and returns cancelled`() = runTest {
        val f = fixture()
        val promise = PromiseResult()
        f.module.pickAndCut("gallery", promise)
        runCurrent()
        val requestId = requireNotNull(f.picks.editorRequestId)

        advanceTimeBy(120_001)
        runCurrent()

        val result = promise.value() as WritableMap
        assertEquals("failed", result.string("status"))
        assertEquals(Messages.CANCELLED, result.string("message"))
        assertNull(f.picks.editorRequestId)
        coVerify(exactly = 0) { f.cutter.cut(any(), any()) }
        assertTrue(requestId.isNotBlank())
    }

    @Test
    fun `module invalidation cancels pending picker and releases its request`() = runTest {
        val f = fixture()
        val promise = PromiseResult()
        f.module.pickAndCut("gallery", promise)
        runCurrent()
        val requestId = requireNotNull(f.picks.editorRequestId)

        f.module.invalidate()
        runCurrent()

        val result = promise.value() as WritableMap
        assertEquals("failed", result.string("status"))
        assertEquals(Messages.CANCELLED, result.string("message"))
        assertNull(f.picks.editorRequestId)
        assertTrue(requestId.isNotBlank())
        coVerify(exactly = 0) { f.cutter.cut(any(), any()) }
    }

    @Test
    fun `module invalidation cancels pending clipboard consume and releases its request`() = runTest {
        val f = fixture()
        val promise = PromiseResult()
        f.module.cutClipboard(promise)
        runCurrent()
        val requestId = requireNotNull(f.clipboard.consumeRequestId)

        f.module.invalidate()
        runCurrent()

        val result = promise.value() as WritableMap
        assertEquals("failed", result.string("status"))
        assertEquals(Messages.CANCELLED, result.string("message"))
        assertNull(f.clipboard.consumeRequestId)
        assertTrue(requestId.isNotBlank())
        coVerify(exactly = 0) { f.cutter.cut(any(), any()) }
    }

    @Test
    fun `model preparation and mask bridge convert thrown failures to stable results`() = runTest {
        val f = fixture()
        coEvery { f.cutter.prepare() } returns Result.success(Unit)
        val ready = PromiseResult()
        f.module.prepareModel(ready)
        assertEquals("ready", ready.value())
        coEvery { f.cutter.prepare() } returns Result.failure(IllegalStateException())
        val unavailable = PromiseResult()
        f.module.prepareModel(unavailable)
        assertEquals("failed", unavailable.value())
        coEvery { f.cutter.prepare() } returns Result.failure(IllegalStateException("model unavailable"))
        val diagnosed = PromiseResult()
        f.module.prepareModel(diagnosed)
        assertEquals("model unavailable", diagnosed.value())

        every { f.masks.readAlpha("throws.png") } throws IllegalStateException("disk")
        val read = PromiseResult()
        f.module.readAlpha("throws.png", read)
        assertEquals("read" to "Could not read mask", read.rejected.await())

        every { f.masks.writeMasked("original.png", "throws", 2, 2) } throws IllegalStateException("disk")
        val write = PromiseResult()
        f.module.writeMasked("original.png", "throws", 2, 2, write)
        val writeResult = write.value() as WritableMap
        assertEquals("failed", writeResult.string("status"))
        assertEquals("write", writeResult.string("message"))

        every { f.masks.publish("throws.png") } throws IllegalStateException("clipboard")
        val publish = PromiseResult()
        f.module.publishPng("throws.png", publish)
        assertEquals("failed", publish.value())
    }

    @Test
    fun `bridge exposes its registered name and reports initial diagnostics baseline`() = runTest {
        val f = fixture()
        assertEquals(EraseModule.NAME, f.module.name)

        val values = JavaOnlyMap().apply {
            putString("surface", "home")
            putString("path", "/private/photo.png")
        }
        val promise = PromiseResult()
        f.module.logProductEvent("screen_view", values, promise)

        assertNull(promise.value())
        val preferences = PromiseResult()
        f.module.getPreferences(preferences)
        val prefs = preferences.value() as WritableMap
        assertTrue(prefs.getBoolean("analytics"))
        assertTrue(prefs.getBoolean("crashes"))
        assertTrue(prefs.getBoolean("diagnosticsNoticePending"))
    }

    @Test
    fun `alpha read failure rejects while masked export maps results and save always settles`() = runTest {
        val f = fixture()
        every { f.masks.readAlpha("missing.png") } returns null
        val read = PromiseResult()
        f.module.readAlpha("missing.png", read)
        assertEquals("read" to "Could not read mask", read.rejected.await())

        every { f.masks.readAlpha("original.png") } returns "YWJj"
        val readReady = PromiseResult()
        f.module.readAlpha("original.png", readReady)
        assertEquals("YWJj", readReady.value())

        every { f.masks.writeMasked("original.png", "YWJj", 2, 2) } returns MaskWrite.Ready("masked.png")
        val write = PromiseResult()
        f.module.writeMasked("original.png", "YWJj", 2, 2, write)
        val written = write.value() as WritableMap
        assertEquals("ready", written.string("status"))
        assertEquals("masked.png", written.string("path"))

        every { f.masks.writeMasked("original.png", "bad", 2, 2) } returns MaskWrite.Failed("alpha")
        val writeFailed = PromiseResult()
        f.module.writeMasked("original.png", "bad", 2, 2, writeFailed)
        val writeFailure = writeFailed.value() as WritableMap
        assertEquals("failed", writeFailure.string("status"))
        assertEquals("alpha", writeFailure.string("message"))
        every { f.masks.writeMasked("original.png", "empty", 2, 2) } returns MaskWrite.Empty("empty.png")
        val emptyWrite = PromiseResult()
        f.module.writeMasked("original.png", "empty", 2, 2, emptyWrite)
        val emptyMask = emptyWrite.value() as WritableMap
        assertEquals("empty", emptyMask.string("status"))
        assertEquals("empty.png", emptyMask.string("path"))

        every { f.masks.publish("masked.png") } returns "copied"
        val published = PromiseResult()
        f.module.publishPng("masked.png", published)
        assertEquals("copied", published.value())
        every { f.masks.publish("empty.png") } returns "empty"
        val empty = PromiseResult()
        f.module.publishPng("empty.png", empty)
        assertEquals("empty", empty.value())

        every { f.gallery.savePng("masked.png") } returns true
        val saved = PromiseResult()
        f.module.saveToGallery("masked.png", saved)
        assertEquals("saved", saved.value())

        every { f.gallery.savePng("bad.png") } throws IllegalStateException("provider")
        val failed = PromiseResult()
        f.module.saveToGallery("bad.png", failed)
        assertEquals("failed", failed.value())
        every { f.gallery.savePng("empty.png") } returns false
        val notSaved = PromiseResult()
        f.module.saveToGallery("empty.png", notSaved)
        assertEquals("failed", notSaved.value())

        coEvery { f.gallery.savePng("cancel.png") } throws kotlinx.coroutines.CancellationException("save cancelled")
        val cancelled = PromiseResult()
        f.module.saveToGallery("cancel.png", cancelled)
        assertEquals("cancelled" to "Save cancelled", cancelled.rejected.await())
    }

    @Test
    @Config(sdk = [28])
    fun `legacy gallery save reports busy when another export owns the result slot`() = runTest {
        val f = fixture()
        val occupied = requireNotNull(dev.kigya.cutout.overlay.LegacyExportActivity.reserve())
        try {
            val result = PromiseResult()

            f.module.saveToGallery("/cache/cutout.png", result)

            assertEquals("failed", result.value())
            assertTrue(f.context.startedActivities.isEmpty())
            verify(exactly = 0) { f.gallery.savePng(any()) }
            assertNull(dev.kigya.cutout.overlay.LegacyExportActivity.reserve())
        } finally {
            dev.kigya.cutout.overlay.LegacyExportActivity.cancel(occupied)
        }
    }

    @Test
    @Config(sdk = [28])
    fun `legacy gallery save launch failure settles and releases its result slot`() = runTest {
        val f = fixture()
        f.context.activityFailure = IllegalStateException("legacy export activity unavailable")
        val result = PromiseResult()

        f.module.saveToGallery("/cache/cutout.png", result)

        assertEquals("failed", result.value())
        val nextOwner = requireNotNull(dev.kigya.cutout.overlay.LegacyExportActivity.reserve())
        dev.kigya.cutout.overlay.LegacyExportActivity.cancel(nextOwner)
        verify(exactly = 0) { f.gallery.savePng(any()) }
    }

    @Test
    @Config(sdk = [28])
    fun `legacy gallery save timeout reports failure and releases its result slot`() = runTest {
        val f = fixture()
        val result = PromiseResult()

        f.module.saveToGallery("/cache/cutout.png", result)
        runCurrent()
        assertEquals(1, f.context.startedActivities.size)
        assertFalse(result.resolved.isCompleted)

        advanceTimeBy(300_001)
        runCurrent()

        assertEquals("failed", result.value())
        val nextOwner = requireNotNull(dev.kigya.cutout.overlay.LegacyExportActivity.reserve())
        dev.kigya.cutout.overlay.LegacyExportActivity.cancel(nextOwner)
        verify(exactly = 0) { f.gallery.savePng(any()) }
    }

    @Test
    @Config(sdk = [28])
    fun `legacy save failure and success each log one export and only success records a review`() = runTest {
        val f = fixture()
        val app = RuntimeEnvironment.getApplication()
        app.getSharedPreferences("cutout_preferences", Context.MODE_PRIVATE).edit()
            .clear().putBoolean("analytics", true).apply()
        val reviewPreferences = app.getSharedPreferences("cutout_review", Context.MODE_PRIVATE)
        reviewPreferences.edit().clear().apply()
        val analytics = mockk<FirebaseAnalytics>(relaxed = true)
        mockkStatic(FirebaseApp::class, FirebaseAnalytics::class)
        every { FirebaseApp.getApps(any()) } returns listOf(mockk())
        every { FirebaseAnalytics.getInstance(any()) } returns analytics
        try {
            f.context.activityFailure = IllegalStateException("activity unavailable")
            val failed = PromiseResult()
            f.module.saveToGallery("/cache/failed.png", failed)
            assertEquals("failed", failed.value())
            assertEquals(0, reviewPreferences.getInt("exports", 0))

            f.context.activityFailure = null
            val saved = PromiseResult()
            f.module.saveToGallery("/cache/saved.png", saved)
            runCurrent()
            val pending = ReflectionHelpers.getStaticField<AtomicReference<CompletableDeferred<String>?>>(
                dev.kigya.cutout.overlay.LegacyExportActivity::class.java,
                "pending",
            )
            requireNotNull(pending.get()).complete("saved")
            runCurrent()

            assertEquals("saved", saved.value())
            verify(exactly = 1) { analytics.logEvent("export_failed", any()) }
            verify(exactly = 1) { analytics.logEvent("export_completed", any()) }
            assertEquals(1, reviewPreferences.getInt("exports", 0))
        } finally {
            unmockkStatic(FirebaseApp::class, FirebaseAnalytics::class)
            app.getSharedPreferences("cutout_preferences", Context.MODE_PRIVATE).edit().clear().apply()
            reviewPreferences.edit().clear().apply()
        }
    }

    @Test
    @Config(sdk = [28])
    fun `module invalidation settles a pending legacy save and releases its result slot`() = runTest {
        val f = fixture()
        val result = PromiseResult()

        f.module.saveToGallery("/cache/cutout.png", result)
        runCurrent()
        assertEquals(1, f.context.startedActivities.size)

        f.module.invalidate()
        runCurrent()

        assertEquals("failed", result.value())
        val nextOwner = requireNotNull(dev.kigya.cutout.overlay.LegacyExportActivity.reserve())
        dev.kigya.cutout.overlay.LegacyExportActivity.cancel(nextOwner)
        verify(exactly = 0) { f.gallery.savePng(any()) }
    }

    @Test
    fun `mask bridge rejects cancellation instead of reporting a successful read or export`() = runTest {
        val f = fixture()
        every { f.masks.readAlpha("cancel.png") } throws kotlinx.coroutines.CancellationException("read cancelled")
        val read = PromiseResult()
        f.module.readAlpha("cancel.png", read)
        assertEquals("cancelled" to "Read cancelled", read.rejected.await())

        every { f.masks.writeMasked("original.png", "mask", 2, 2) } throws
            kotlinx.coroutines.CancellationException("export cancelled")
        val write = PromiseResult()
        f.module.writeMasked("original.png", "mask", 2, 2, write)
        assertEquals("cancelled" to "Export cancelled", write.rejected.await())

        every { f.masks.publish("original.png") } throws kotlinx.coroutines.CancellationException("copy cancelled")
        val publish = PromiseResult()
        f.module.publishPng("original.png", publish)
        assertEquals("cancelled" to "Copy cancelled", publish.rejected.await())
    }

    @Test
    fun `cancel cut while idle is harmless and settles its promise`() = runTest {
        val f = fixture()
        val cancel = PromiseResult()

        f.module.cancelCut(cancel)

        assertNull(cancel.value())
        val next = PromiseResult()
        coEvery { f.cutter.cutFixture(any()) } returns CutOutcome.Failed("still available")
        f.module.cutFixture(next)
        val outcome = next.value() as WritableMap
        assertEquals("failed", outcome.string("status"))
        assertEquals("still available", outcome.string("message"))
    }

    @Test
    fun `canceling clipboard acquisition settles once and clears the correlated request`() = runTest {
        val f = fixture()
        val cut = PromiseResult()
        f.module.cutClipboard(cut)
        runCurrent()
        val requestId = requireNotNull(f.clipboard.consumeRequestId)
        assertTrue(f.context.startedActivities.single().getBooleanExtra("consume", false))

        val cancel = PromiseResult()
        f.module.cancelCut(cancel)
        assertNull(cancel.value())
        runCurrent()

        val result = cut.value() as WritableMap
        assertEquals("failed", result.string("status"))
        assertEquals(Messages.CANCELLED, result.string("message"))
        assertNull(f.clipboard.consumeRequestId)
        coVerify(exactly = 0) { f.cutter.cut(any(), any()) }
        assertTrue(requestId.isNotBlank())
    }

    @Test
    fun `cancelling an older cut does not lose the replacement cut handle`() = runTest {
        val f = fixture()
        val callCount = AtomicInteger()
        val firstStarted = CompletableDeferred<Unit>()
        val replacementStarted = CompletableDeferred<Unit>()
        coEvery { f.cutter.cutFixture(any()) } coAnswers {
            when (callCount.getAndIncrement()) {
                0 -> firstStarted.complete(Unit)
                1 -> replacementStarted.complete(Unit)
            }
            kotlinx.coroutines.awaitCancellation()
        }

        val older = PromiseResult()
        f.module.cutFixture(older)
        firstStarted.await()

        val replacement = PromiseResult()
        f.module.cutFixture(replacement)
        replacementStarted.await()
        assertEquals(Messages.CANCELLED, (older.value() as WritableMap).string("message"))

        val cancel = PromiseResult()
        f.module.cancelCut(cancel)
        runCurrent()

        assertNull(cancel.value())
        assertEquals(Messages.CANCELLED, (replacement.value() as WritableMap).string("message"))
    }

    @Test
    fun `clipboard empty and toggle action preserve public map shape`() = runTest {
        val f = fixture()
        val state = PromiseResult()
        f.module.getBubbleState(state)
        val bubbleState = state.value() as WritableMap
        assertEquals("idle", bubbleState.string("phase"))
        assertEquals(0, bubbleState.getInt("percent"))
        assertTrue(bubbleState.hasKey("readingClipboard"))

        val result = PromiseResult()
        f.module.cutClipboard(result)
        runCurrent()
        val requestId = requireNotNull(f.clipboard.consumeRequestId)
        f.clipboard.offer(ClipboardRead(ClipboardLook.Empty, consume = true, requestId = requestId))
        val failed = result.value() as WritableMap
        assertEquals("failed", failed.string("status"))
        assertEquals(Messages.EMPTY_CLIPBOARD, failed.string("message"))

        val action = PromiseResult()
        f.module.bubbleAction("toggle", action)
        val toggled = action.value() as WritableMap
        assertEquals("idle", toggled.string("phase"))
        assertTrue(toggled.getBoolean("expanded"))
        assertEquals(1, toggled.getInt("revision"))

        val unknown = PromiseResult()
        f.module.bubbleAction("not-an-action", unknown)
        assertEquals("action" to "not-an-action", unknown.rejected.await())
    }

    @Test
    fun `all recognized bubble actions resolve after state transition`() = runTest {
        val f = fixture()
        coEvery { f.presenter.onEvent(any()) } answers {
            val event = firstArg<BubbleUiEvent>()
            f.state.value = f.state.value.copy(
                revision = f.state.value.revision + 1,
                expanded = event !in setOf(BubbleUiEvent.Dismiss, BubbleUiEvent.Collapse),
                phase = if (event in setOf(BubbleUiEvent.FromClipboard, BubbleUiEvent.FromGallery, BubbleUiEvent.FromFile)) {
                    BubblePhase.Copied
                } else {
                    f.state.value.phase
                },
            )
        }
        listOf("clipboard", "gallery", "file", "refine", "dismiss", "collapse", "again").forEach { action ->
            f.state.value = f.state.value.copy(expanded = true)
            val result = PromiseResult()
            f.module.bubbleAction(action, result)
            val state = result.value() as WritableMap
            assertTrue("$action must advance the public state revision", state.getInt("revision") > 0)
            assertEquals(action !in listOf("dismiss", "collapse"), state.getBoolean("expanded"))
            if (action in listOf("clipboard", "gallery", "file")) assertEquals("copied", state.string("phase"))
        }
        coVerify(exactly = 1) { f.presenter.onEvent(BubbleUiEvent.FromClipboard) }
        coVerify(exactly = 1) { f.presenter.onEvent(BubbleUiEvent.FromGallery) }
        coVerify(exactly = 1) { f.presenter.onEvent(BubbleUiEvent.FromFile) }
        coVerify(exactly = 1) { f.presenter.onEvent(BubbleUiEvent.Refine) }
        coVerify(exactly = 1) { f.presenter.onEvent(BubbleUiEvent.Dismiss) }
        coVerify(exactly = 1) { f.presenter.onEvent(BubbleUiEvent.Collapse) }
        coVerify(exactly = 1) { f.presenter.onEvent(BubbleUiEvent.Again) }
    }

    @Test
    fun `collapse on an already collapsed bubble resolves without waiting or changing the result`() = runTest {
        val f = fixture()
        f.state.value = BubbleUiState(
            revision = 7,
            expanded = false,
            phase = BubblePhase.Copied,
            message = Messages.COPIED,
            previewPath = "/cache/result.png",
            percent = 100,
        )
        val promise = PromiseResult()
        f.module.bubbleAction("collapse", promise)

        val snapshot = promise.value() as WritableMap
        assertEquals(7, snapshot.getInt("revision"))
        assertFalse(snapshot.getBoolean("expanded"))
        assertEquals("copied", snapshot.string("phase"))
        assertEquals(Messages.COPIED, snapshot.string("message"))
        assertEquals("/cache/result.png", snapshot.string("previewPath"))
        assertEquals(100, snapshot.getInt("percent"))
        coVerify(exactly = 0) { f.presenter.onEvent(any()) }
    }

    @Test
    fun `permission completion records a live denial once and ignores unknown status`() = runTest {
        val f = fixture()
        every { f.permissions.family() } returns OemFamily.Generic
        every { f.permissions.snapshot() } returns listOf(
            PermissionSnapshot(PermissionKind.Overlay, GrantStatus.Denied),
        )
        val begin = PromiseResult()
        f.module.beginPermissionRequest("overlay", begin)
        val attempt = begin.value() as String

        val complete = PromiseResult()
        f.module.completePermissionRequest("overlay", attempt, true, complete)
        complete.value()
        val repeated = PromiseResult()
        f.module.completePermissionRequest("overlay", attempt, true, repeated)
        repeated.value()

        val home = PromiseResult()
        f.module.getHome(home)
        val row = (home.value() as WritableMap).getArray("rows")!!.getMap(0)!!
        assertEquals(1, row.getInt("deniedCount"))
        assertTrue(row.getBoolean("permanentlyDenied"))
        assertEquals("optional", row.string("need"))

        every { f.permissions.snapshot() } returns listOf(
            PermissionSnapshot(PermissionKind.Overlay, GrantStatus.Unknown),
        )
        val unknownBegin = PromiseResult()
        f.module.beginPermissionRequest("overlay", unknownBegin)
        val unknownAttempt = unknownBegin.value() as String
        val unknownComplete = PromiseResult()
        f.module.completePermissionRequest("overlay", unknownAttempt, true, unknownComplete)
        unknownComplete.value()
        val unknownHome = PromiseResult()
        f.module.getHome(unknownHome)
        val unknownRow = (unknownHome.value() as WritableMap).getArray("rows")!!.getMap(0)!!
        assertEquals(1, unknownRow.getInt("deniedCount"))
        assertFalse(unknownRow.getBoolean("permanentlyDenied"))
        assertEquals("unknown", unknownRow.string("status"))
    }

    @Test
    fun `bubble action waits for asynchronous state publication before resolving`() = runTest {
        val f = fixture()
        coEvery { f.presenter.onEvent(BubbleUiEvent.Toggle) } answers {
            backgroundScope.launch {
                kotlinx.coroutines.delay(1)
                f.state.value = f.state.value.copy(revision = f.state.value.revision + 1, expanded = true)
            }
        }

        val promise = PromiseResult()
        f.module.bubbleAction("toggle", promise)
        runCurrent()
        assertFalse(promise.resolved.isCompleted)

        advanceTimeBy(1)
        runCurrent()

        val state = promise.value() as WritableMap
        assertTrue(state.getBoolean("expanded"))
        assertEquals(1, state.getInt("revision"))
    }

    @Test
    fun `clipboard action ignores intermediate working state until a terminal state arrives`() = runTest {
        val f = fixture()
        coEvery { f.presenter.onEvent(BubbleUiEvent.FromClipboard) } answers {
            backgroundScope.launch {
                f.state.value = f.state.value.copy(revision = f.state.value.revision + 1, phase = BubblePhase.Working)
                kotlinx.coroutines.delay(1)
                f.state.value = f.state.value.copy(revision = f.state.value.revision + 1, phase = BubblePhase.Copied)
            }
        }

        val promise = PromiseResult()
        f.module.bubbleAction("clipboard", promise)
        runCurrent()
        assertFalse(promise.resolved.isCompleted)

        advanceTimeBy(1)
        runCurrent()

        val state = promise.value() as WritableMap
        assertEquals("copied", state.string("phase"))
        assertEquals(2, state.getInt("revision"))
    }

    @Test
    fun `bridge action resolves the latest state when a long-running action times out`() = runTest {
        val f = fixture()
        coEvery { f.presenter.onEvent(BubbleUiEvent.FromClipboard) } coAnswers {
            f.state.value = f.state.value.copy(revision = f.state.value.revision + 1, phase = BubblePhase.Working)
        }
        val action = PromiseResult()
        f.module.bubbleAction("clipboard", action)
        runCurrent()
        assertFalse(action.resolved.isCompleted)

        advanceTimeBy(180_001)
        runCurrent()
        val state = action.value() as WritableMap
        assertEquals("working", state.string("phase"))
        assertEquals(1, state.getInt("revision"))
    }

    @Test
    fun `clipboard bubble action resolves when its terminal state is failed`() = runTest {
        val f = fixture()
        coEvery { f.presenter.onEvent(BubbleUiEvent.FromClipboard) } answers {
            f.state.value = f.state.value.copy(
                revision = f.state.value.revision + 1,
                phase = BubblePhase.Failed,
                message = Messages.READ_FAILED,
            )
        }

        val promise = PromiseResult()
        f.module.bubbleAction("clipboard", promise)
        val state = promise.value() as WritableMap

        assertEquals("failed", state.string("phase"))
        assertEquals(Messages.READ_FAILED, state.string("message"))
        assertEquals(1, state.getInt("revision"))
    }

    @Test
    fun `preferences locale pending edits and window callbacks honor bridge contracts`() = runTest {
        val f = fixture()
        val defaultPreferences = PromiseResult()
        f.module.getPreferences(defaultPreferences)
        val defaults = defaultPreferences.value() as WritableMap
        assertEquals("system", defaults.string("theme"))
        assertTrue(defaults.getBoolean("analytics"))
        assertTrue(defaults.getBoolean("crashes"))
        assertTrue(defaults.getBoolean("diagnosticsNoticePending"))

        val changed = PromiseResult()
        f.module.setPreferences("garish", analytics = true, crashes = false, promise = changed)
        assertNull(changed.value())
        val updatedPreferences = PromiseResult()
        f.module.getPreferences(updatedPreferences)
        val updated = updatedPreferences.value() as WritableMap
        assertEquals("system", updated.string("theme"))
        assertTrue(updated.getBoolean("analytics"))
        assertFalse(updated.getBoolean("crashes"))
        assertTrue(updated.getBoolean("diagnosticsNoticePending"))

        val acknowledged = PromiseResult()
        f.module.acknowledgeDiagnosticsNotice(acknowledged)
        assertNull(acknowledged.value())
        val afterAcknowledgement = PromiseResult()
        f.module.getPreferences(afterAcknowledgement)
        val finalPreferences = afterAcknowledgement.value() as WritableMap
        assertFalse(finalPreferences.getBoolean("diagnosticsNoticePending"))
        assertTrue(finalPreferences.getBoolean("analytics"))
        assertFalse(finalPreferences.getBoolean("crashes"))

        val localeBefore = PromiseResult()
        f.module.getLocale(localeBefore)
        val currentLocale = localeBefore.value() as WritableMap
        assertTrue(currentLocale.string("system")!!.isNotBlank())
        val setLocale = PromiseResult()
        f.module.setLocale("ru", setLocale)
        assertNull(setLocale.value())
        val localeAfter = PromiseResult()
        f.module.getLocale(localeAfter)
        assertEquals("ru", (localeAfter.value() as WritableMap).string("selected"))
        f.module.setLocale("", PromiseResult())

        val files = EditFiles("original.png", "cutout.png", 5, 4)
        every { f.sessions.consumeOpen() } returns files
        val pending = PromiseResult()
        f.module.consumePendingEdit(pending)
        val edit = pending.value() as WritableMap
        assertEquals("ready", edit.string("status"))
        assertEquals("cutout.png", edit.string("cutoutPath"))
        every { f.sessions.consumeOpen() } returns null
        val noPending = PromiseResult()
        f.module.consumePendingEdit(noPending)
        assertNull(noPending.value())

        val moved = mutableListOf<Pair<Float, Float>>()
        val resized = mutableListOf<Pair<Float, Float>>()
        f.window.move = { x, y -> moved += x to y }
        f.window.resize = { width, height -> resized += width to height }
        f.window.settle = { false }
        f.module.moveBubble(3.5, -2.0)
        f.module.setBubbleSize(200.0, 90.0)
        val settled = PromiseResult()
        f.module.settleBubble(settled)
        assertEquals(listOf(3.5f to -2.0f), moved)
        assertEquals(listOf(200f to 90f), resized)
        assertEquals(false, settled.value())
    }

    @Test
    fun `active React instance receives preference locale bubble and cut progress events`() = runTest {
        val f = fixture()
        f.context.activeReactInstance = true

        val preferences = PromiseResult()
        f.module.setPreferences("dark", analytics = true, crashes = true, promise = preferences)
        assertNull(preferences.value())
        val preferencesEvent = f.context.deviceEvents.single { it.first == "preferencesChanged" }.second as ReadableMap
        assertEquals("dark", preferencesEvent.getString("theme"))
        assertTrue(preferencesEvent.getBoolean("analytics"))
        assertTrue(preferencesEvent.getBoolean("crashes"))

        f.state.value = f.state.value.copy(revision = 1, expanded = true, percent = 45)
        (f.preferences.enabled as MutableStateFlow<Boolean>).value = true
        assertTrue(f.context.deviceEvents.any { it.first == "bubbleState" })
        assertTrue(f.context.deviceEvents.any { it.first == "bubbleEnabled" })

        val selectedLocale = PromiseResult()
        f.module.setLocale("ru", selectedLocale)
        assertNull(selectedLocale.value())
        assertTrue(f.context.deviceEvents.any { it.first == "eraseLocale" })

        coEvery { f.cutter.cutFixture(any()) } coAnswers {
            val listener = firstArg<dev.kigya.cutout.cutout.CutListener>()
            listener.onProgress(130)
            listener.onPreview("/cache/preview.png")
            CutOutcome.CopiedSvg(Messages.COPIED)
        }
        val cut = PromiseResult()
        f.module.cutFixture(cut)
        assertEquals("svg", (cut.value() as WritableMap).string("status"))
        val progress = f.context.deviceEvents.last { it.first == "eraseProgress" }.second as ReadableMap
        assertEquals(100, progress.getInt("percent"))
        val preview = f.context.deviceEvents.last { it.first == "erasePreview" }.second as ReadableMap
        assertEquals("/cache/preview.png", preview.getString("path"))
    }

    @Test
    fun `detached React instance suppresses native events while bridge operations still resolve`() = runTest {
        val f = fixture()
        f.state.value = f.state.value.copy(revision = 2, expanded = true)
        (f.preferences.enabled as MutableStateFlow<Boolean>).value = true
        f.module.setPreferences("dark", analytics = true, crashes = false, promise = PromiseResult())
        f.module.setLocale("de", PromiseResult())
        coEvery { f.cutter.cutFixture(any()) } coAnswers {
            val listener = firstArg<dev.kigya.cutout.cutout.CutListener>()
            listener.onProgress(50)
            listener.onPreview("/cache/preview.png")
            CutOutcome.CopiedSvg(Messages.COPIED)
        }
        val result = PromiseResult()
        f.module.cutFixture(result)

        assertEquals("svg", (result.value() as WritableMap).string("status"))
        assertTrue(f.context.deviceEvents.isEmpty())
    }

    @Test
    fun `preference theme updates system bar appearance for explicit and system themes`() = runTest {
        val f = fixture()
        val activityController = org.robolectric.Robolectric.buildActivity(Activity::class.java).setup()
        val activity = activityController.get()
        f.context.foregroundActivity = activity
        val bars = androidx.core.view.WindowCompat.getInsetsController(activity.window, activity.window.decorView)

        fun applySystemNight(night: Boolean) {
            val configuration = Configuration(activity.resources.configuration)
            configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                if (night) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
            @Suppress("DEPRECATION")
            activity.resources.updateConfiguration(configuration, activity.resources.displayMetrics)
        }

        applySystemNight(night = false)
        f.module.setPreferences("system", analytics = false, crashes = false, promise = PromiseResult())
        assertTrue(bars.isAppearanceLightStatusBars)
        assertTrue(bars.isAppearanceLightNavigationBars)

        applySystemNight(night = true)
        f.module.setPreferences("system", analytics = false, crashes = false, promise = PromiseResult())
        assertFalse(bars.isAppearanceLightStatusBars)
        assertFalse(bars.isAppearanceLightNavigationBars)

        f.module.setPreferences("light", analytics = false, crashes = false, promise = PromiseResult())
        assertTrue(bars.isAppearanceLightStatusBars)
        f.module.setPreferences("dark", analytics = false, crashes = false, promise = PromiseResult())
        assertFalse(bars.isAppearanceLightStatusBars)
        activityController.destroy()
    }

    @Test
    @Config(sdk = [32])
    fun `locale changes before Android 13 settle without a foreground activity`() = runTest {
        val f = fixture()
        val changed = PromiseResult()
        f.module.setLocale("fr", changed)
        assertNull(changed.value())
        val locale = PromiseResult()
        f.module.getLocale(locale)
        assertEquals("fr", (locale.value() as WritableMap).string("selected"))

        val restarted = PromiseResult()
        f.module.restart(restarted)
        assertNull(restarted.value())
    }

    @Test
    @Config(sdk = [32])
    fun `legacy locale changes and restart recreate the current activity`() = runTest {
        val f = fixture()
        val activity = mockk<Activity>(relaxed = true)
        f.context.foregroundActivity = activity

        f.module.setLocale("it", PromiseResult())
        f.module.restart(PromiseResult())

        verify(exactly = 2) { activity.recreate() }
    }

    @Test
    fun `unset window callbacks return defaults without mutating a service`() = runTest {
        val f = fixture()
        f.module.moveBubble(12.0, -5.0)
        f.module.setBubbleSize(180.0, 64.0)
        val settled = PromiseResult()
        f.module.settleBubble(settled)
        assertEquals(true, settled.value())
    }

    @Test
    @Config(sdk = [28])
    fun `legacy gallery export rejects a busy request and settles launch failures`() = runTest {
        val f = fixture()
        val reserved = requireNotNull(dev.kigya.cutout.overlay.LegacyExportActivity.reserve())
        val busy = PromiseResult()
        f.module.saveToGallery("/cache/picture.png", busy)
        assertEquals("failed", busy.value())
        assertTrue(f.context.startedActivities.isEmpty())
        dev.kigya.cutout.overlay.LegacyExportActivity.cancel(reserved)

        f.context.activityFailure = IllegalStateException("legacy exporter unavailable")
        val launchFailed = PromiseResult()
        f.module.saveToGallery("/cache/picture.png", launchFailed)
        assertEquals("failed", launchFailed.value())
        val released = dev.kigya.cutout.overlay.LegacyExportActivity.reserve()
        assertTrue(released != null)
        dev.kigya.cutout.overlay.LegacyExportActivity.cancel(requireNotNull(released))
    }

    @Test
    @Config(sdk = [28])
    fun `legacy gallery export times out and releases its outstanding request`() = runTest {
        val f = fixture()
        val saving = PromiseResult()
        f.module.saveToGallery("/cache/picture.png", saving)
        assertEquals("/cache/picture.png", f.context.startedActivities.single().getStringExtra("path"))

        advanceTimeBy(300_001)
        runCurrent()

        assertEquals("failed", saving.value())
        val next = dev.kigya.cutout.overlay.LegacyExportActivity.reserve()
        assertTrue("timed out export request must be released", next != null)
        dev.kigya.cutout.overlay.LegacyExportActivity.cancel(requireNotNull(next))
    }

    @Test
    @Config(sdk = [28])
    fun `legacy gallery export cancellation settles failed and releases its request`() = runTest {
        val f = fixture()
        val saving = PromiseResult()
        f.module.saveToGallery("/cache/picture.png", saving)
        assertEquals(1, f.context.startedActivities.size)

        f.module.invalidate()
        runCurrent()

        assertEquals("failed", saving.value())
        val next = dev.kigya.cutout.overlay.LegacyExportActivity.reserve()
        assertTrue("cancelled export request must be released", next != null)
        dev.kigya.cutout.overlay.LegacyExportActivity.cancel(requireNotNull(next))
    }
}
