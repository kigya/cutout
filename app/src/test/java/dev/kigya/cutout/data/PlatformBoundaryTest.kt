package dev.kigya.cutout.data

import android.app.Application
import android.app.AppOpsManager
import android.app.LocaleManager
import android.content.Context
import android.os.LocaleList
import dev.kigya.cutout.domain.OemFamily
import dev.kigya.cutout.domain.PermissionKind
import dev.kigya.cutout.domain.SettingsAttempt
import dev.kigya.cutout.locale.AppLocales
import dev.kigya.cutout.permissions.GrantStatus
import dev.kigya.cutout.permissions.PermissionCenter
import dev.kigya.cutout.permissions.SettingsNavigator
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PlatformBoundaryTest {
    @After fun clearMocks() { unmockkAll() }
    @Test fun `settings refuses incomplete targets and preserves explicit component action`() {
        val context = mockk<Context>(relaxed = true)
        val navigator = SettingsNavigator(context, mockk(relaxed = true))
        fun start(target: SettingsAttempt): Boolean = ReflectionHelpers.callInstanceMethod(navigator, "start", ReflectionHelpers.ClassParameter.from(SettingsAttempt::class.java, target))
        assertFalse(start(SettingsAttempt()))
        assertFalse(start(SettingsAttempt(componentPackage = "vendor")))
        assertTrue(start(SettingsAttempt(componentClass = "Settings", action = "system.SETTINGS")))
        assertTrue(start(SettingsAttempt(componentPackage = "vendor", componentClass = "Settings", action = "vendor.SETTINGS")))
        verify(exactly = 2) { context.startActivity(any()) }
    }
    @Test fun `unsupported OEM background status stays unknown rather than claiming denial`() {
        val center = PermissionCenter(mockk(relaxed = true))
        val result = ReflectionHelpers.callInstanceMethod<GrantStatus>(center, "status",
            ReflectionHelpers.ClassParameter.from(PermissionKind::class.java, PermissionKind.BackgroundPopup),
            ReflectionHelpers.ClassParameter.from(OemFamily::class.java, OemFamily.Generic))
        assertEquals(GrantStatus.Unknown, result)
    }
    @Test fun `OEM AppOps reflection failure reports unknown status`() {
        val context = mockk<Context>()
        every { context.packageName } returns "dev.kigya.cutout"
        every { context.getSystemService(AppOpsManager::class.java) } throws NoSuchMethodException("OEM service lookup unavailable")
        val result = ReflectionHelpers.callInstanceMethod<GrantStatus>(PermissionCenter(context), "appOp", ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType, 10008))
        assertEquals(GrantStatus.Unknown, result)
    }
    @Test fun `empty system locale falls back to English and blank override restores system selection`() {
        val manager = mockk<LocaleManager>(relaxed = true)
        val context = mockk<Context>()
        every { context.getSystemService(LocaleManager::class.java) } returns manager
        every { manager.systemLocales } returns LocaleList.getEmptyLocaleList()
        assertEquals("en", AppLocales.systemTag(context))
        AppLocales.apply(context, "")
        AppLocales.apply(context, null)
        verify(exactly = 2) { manager.applicationLocales = LocaleList.getEmptyLocaleList() }
    }
}
