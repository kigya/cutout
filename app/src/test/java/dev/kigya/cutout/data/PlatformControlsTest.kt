package dev.kigya.cutout.data

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.ActivityNotFoundException
import android.app.AppOpsManager
import android.Manifest
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import dev.kigya.cutout.domain.PermissionKind
import dev.kigya.cutout.locale.AppLocales
import dev.kigya.cutout.permissions.GrantStatus
import dev.kigya.cutout.permissions.PermissionCenter
import dev.kigya.cutout.permissions.SettingsNavigator
import io.mockk.every
import io.mockk.mockk
import org.junit.After
import org.junit.Before
import org.robolectric.Shadows
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowSettings

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlatformControlsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private var manufacturer: String = ""
    private var brand: String = ""
    @Before fun rememberBuildIdentity() {
        manufacturer = Build.MANUFACTURER
        brand = Build.BRAND
        app.getSharedPreferences("cutout_preferences", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @After fun restoreBuildIdentity() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", manufacturer)
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", brand)
    }
    @Test fun `diagnostics baseline is enabled once and theme is validated without Firebase configuration`() {
        val telemetry = AppTelemetry(app)
        assertTrue(telemetry.analyticsEnabled)
        assertTrue(telemetry.crashEnabled)
        assertTrue(telemetry.diagnosticsNoticePending)
        assertEquals("system", telemetry.theme)
        telemetry.event("cutout_completed", mapOf("source" to "clipboard"))
        telemetry.update("dark", true, true)
        assertEquals("dark", AppTelemetry(app).theme)
        assertTrue(telemetry.analyticsEnabled)
        assertTrue(telemetry.crashEnabled)
        telemetry.update("dark", false, false)
        val optedOut = AppTelemetry(app)
        assertFalse(optedOut.analyticsEnabled)
        assertFalse(optedOut.crashEnabled)
        assertTrue(optedOut.diagnosticsNoticePending)
        optedOut.acknowledgeDiagnosticsNotice()
        assertFalse(AppTelemetry(app).diagnosticsNoticePending)
        telemetry.event("not_allowed", mapOf("path" to "/private/photo.png"))
        telemetry.update("invalid", false, false)
        assertEquals("system", telemetry.theme)
        assertFalse(telemetry.analyticsEnabled)
        assertFalse(telemetry.crashEnabled)
    }
    @Test fun `generic permission snapshot reports optional notification denial and actual overlay grant`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "Pixel")
        ShadowSettings.setCanDrawOverlays(false)
        val permissions = PermissionCenter(app)
        assertEquals(listOf(PermissionKind.Overlay, PermissionKind.Battery, PermissionKind.Notifications), permissions.snapshot().map { it.kind })
        val initial = permissions.snapshot().associate { it.kind to it.status }
        assertEquals(GrantStatus.Denied, initial[PermissionKind.Overlay])
        assertEquals(GrantStatus.Denied, initial[PermissionKind.Battery])
        assertEquals(GrantStatus.Denied, initial[PermissionKind.Notifications])
        ShadowSettings.setCanDrawOverlays(true)
        assertEquals(GrantStatus.Granted, permissions.snapshot().first().status)
    }
    @Test fun `permission snapshot reports granted notification and battery states`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "Pixel")
        val power = app.getSystemService(PowerManager::class.java)
        Shadows.shadowOf(power).setIgnoringBatteryOptimizations(app.packageName, true)
        Shadows.shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)

        val statuses = PermissionCenter(app).snapshot().associate { it.kind to it.status }

        assertEquals(GrantStatus.Granted, statuses[PermissionKind.Battery])
        assertEquals(GrantStatus.Granted, statuses[PermissionKind.Notifications])
    }
    @Test @Config(sdk = [32]) fun `notifications are granted before Android 13 adds the runtime permission`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "Pixel")

        val status = PermissionCenter(app).snapshot().first { it.kind == PermissionKind.Notifications }.status

        assertEquals(GrantStatus.Granted, status)
    }
    @Test fun `Xiaomi snapshot reads AppOps modes and degrades to unknown when the service is unavailable`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Xiaomi")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "Redmi")
        val appOps = app.getSystemService(AppOpsManager::class.java)
        val shadowOps = Shadows.shadowOf(appOps)
        shadowOps.setMode(10008, android.os.Process.myUid(), app.packageName, AppOpsManager.MODE_ALLOWED)
        shadowOps.setMode(10021, android.os.Process.myUid(), app.packageName, AppOpsManager.MODE_IGNORED)
        val current = PermissionCenter(app).snapshot().associate { it.kind to it.status }
        assertEquals(GrantStatus.Granted, current[PermissionKind.Autostart])
        assertEquals(GrantStatus.Denied, current[PermissionKind.BackgroundPopup])

        val noAppOps = mockk<Context>(relaxed = true)
        every { noAppOps.packageName } returns app.packageName
        every { noAppOps.contentResolver } returns app.contentResolver
        every { noAppOps.checkSelfPermission(any()) } returns android.content.pm.PackageManager.PERMISSION_GRANTED
        every { noAppOps.getSystemService(AppOpsManager::class.java) } returns null
        every { noAppOps.getSystemService(PowerManager::class.java) } returns app.getSystemService(PowerManager::class.java)
        val unavailable = PermissionCenter(noAppOps).snapshot().associate { it.kind to it.status }
        assertEquals(GrantStatus.Unknown, unavailable[PermissionKind.Autostart])
        assertEquals(GrantStatus.Unknown, unavailable[PermissionKind.BackgroundPopup])
    }
    @Test fun `OEM settings failures fall back to system app details`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Xiaomi")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "Redmi")
        val attempts = mutableListOf<Intent>()
        val context = object : android.content.ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                attempts += intent
                if (attempts.size == 1) throw ActivityNotFoundException("not installed")
                if (attempts.size == 2) throw SecurityException("OEM restrictions")
            }
        }
        SettingsNavigator(context, PermissionCenter(context)).open(PermissionKind.Autostart)
        assertEquals(3, attempts.size)
        assertEquals("com.miui.permcenter.autostart.AutoStartManagementActivity", attempts[0].component!!.className)
        assertEquals(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, attempts.last().action)
        assertEquals("package:${app.packageName}", attempts.last().data.toString())
        assertTrue(attempts.all { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
    }
    @Test fun `settings navigator stops quietly when all OEM and system destinations are missing`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Xiaomi")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "Redmi")
        val attempts = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) {
                attempts += intent
                throw ActivityNotFoundException("settings screen unavailable")
            }
        }

        SettingsNavigator(context, PermissionCenter(context)).open(PermissionKind.Autostart)

        assertEquals(3, attempts.size)
        assertTrue(attempts.all { it.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0 })
    }
    @Test fun `successful notification settings launch uses the package extra and does not fall through`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "Pixel")
        val attempts = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { attempts += intent }
        }

        SettingsNavigator(context, PermissionCenter(context)).open(PermissionKind.Notifications)

        assertEquals(1, attempts.size)
        assertEquals(Settings.ACTION_APP_NOTIFICATION_SETTINGS, attempts.single().action)
        assertEquals(app.packageName, attempts.single().getStringExtra(Settings.EXTRA_APP_PACKAGE))
        assertTrue(attempts.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
    @Test fun `battery settings launch scopes the exemption request to this package`() {
        ReflectionHelpers.setStaticField(Build::class.java, "MANUFACTURER", "Google")
        ReflectionHelpers.setStaticField(Build::class.java, "BRAND", "Pixel")
        val attempts = mutableListOf<Intent>()
        val context = object : ContextWrapper(app) {
            override fun startActivity(intent: Intent) { attempts += intent }
        }

        SettingsNavigator(context, PermissionCenter(context)).open(PermissionKind.Battery)

        assertEquals(1, attempts.size)
        assertEquals(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, attempts.single().action)
        assertEquals("package:${app.packageName}", attempts.single().data.toString())
        assertTrue(attempts.single().flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }
    @Test fun `API33 locale override and system selection use Android LocaleManager`() {
        AppLocales.apply(app, "ar")
        assertEquals("ar", AppLocales.selectedTag(app))
        assertSame(app, AppLocales.wrap(app))
        AppLocales.apply(app, null)
        assertNull(AppLocales.selectedTag(app))
        assertTrue(AppLocales.systemTag(app).isNotBlank())
    }
    @Test @Config(sdk = [31]) fun `pre33 locale override persists and RTL wrapper is scoped`() {
        val previous = Locale.getDefault()
        try {
            assertNull(AppLocales.selectedTag(app))
            assertSame(app, AppLocales.wrap(app))
            AppLocales.apply(app, "ar")
            assertEquals("ar", AppLocales.selectedTag(app))
            val wrapped = AppLocales.wrap(app)
            assertEquals("ar", wrapped.resources.configuration.locales[0].language)
            assertEquals(android.view.View.LAYOUT_DIRECTION_RTL, wrapped.resources.configuration.layoutDirection)
            AppLocales.apply(app, null)
            assertNull(AppLocales.selectedTag(app))
            assertSame(app, AppLocales.wrap(app))
            AppLocales.apply(app, "")
            assertNull(AppLocales.selectedTag(app))
            assertSame(app, AppLocales.wrap(app))
            assertTrue(AppLocales.systemTag(app).isNotBlank())
            assertEquals(GrantStatus.Granted, PermissionCenter(app).snapshot().last().status)
        } finally { Locale.setDefault(previous) }
    }
}
