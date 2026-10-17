package dev.kigya.cutout.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OemRoutingTest {
    @Test fun `classifies manufacturer and brand aliases case insensitively`() {
        val cases = listOf(
            Triple("Xiaomi", "", OemFamily.Xiaomi),
            Triple("", "Redmi", OemFamily.Xiaomi),
            Triple("Poco", "", OemFamily.Xiaomi),
            Triple("BlackShark", "", OemFamily.Xiaomi),
            Triple("OPPO", "", OemFamily.Oppo),
            Triple("", "realme", OemFamily.Oppo),
            Triple("OnePlus", "", OemFamily.Oppo),
            Triple("vivo", "", OemFamily.Vivo),
            Triple("", "iQOO", OemFamily.Vivo),
            Triple("Huawei", "", OemFamily.Huawei),
            Triple("", "HONOR", OemFamily.Huawei),
            Triple("Google", "Pixel", OemFamily.Generic),
            Triple("", "", OemFamily.Generic),
        )

        cases.forEach { (manufacturer, brand, expected) ->
            assertEquals("$manufacturer / $brand", expected, oemFamily(manufacturer, brand))
        }
    }

    @Test fun `exposes only the permission rows applicable to each family`() {
        val cases = listOf(
            OemFamily.Xiaomi to listOf(
                PermissionKind.Overlay,
                PermissionKind.BackgroundPopup,
                PermissionKind.Autostart,
                PermissionKind.Battery,
                PermissionKind.Notifications,
            ),
            OemFamily.Oppo to listOf(
                PermissionKind.Overlay,
                PermissionKind.Autostart,
                PermissionKind.Battery,
                PermissionKind.Notifications,
            ),
            OemFamily.Vivo to listOf(
                PermissionKind.Overlay,
                PermissionKind.Autostart,
                PermissionKind.Battery,
                PermissionKind.Notifications,
            ),
            OemFamily.Huawei to listOf(
                PermissionKind.Overlay,
                PermissionKind.Autostart,
                PermissionKind.Battery,
                PermissionKind.Notifications,
            ),
            OemFamily.Generic to listOf(
                PermissionKind.Overlay,
                PermissionKind.Battery,
                PermissionKind.Notifications,
            ),
        )

        cases.forEach { (family, expected) -> assertEquals(family.name, expected, visiblePermissions(family)) }
    }

    @Test fun `overlay alone is required for Bubble and no optional setting blocks in-app cutting`() {
        PermissionKind.entries.forEach { kind ->
            assertEquals(
                if (kind == PermissionKind.Overlay) PermissionNeed.RequiredForBubble else PermissionNeed.Optional,
                permissionNeed(kind),
            )
            assertFalse("$kind must not block in-app cutout", blocksInAppCutout(kind))
        }
    }

    @Test fun `overlay battery notification and Xiaomi popup routes retain system fallbacks`() {
        val details = appDetails()
        assertEquals(
            listOf(
                SettingsAttempt(action = "android.settings.action.MANAGE_OVERLAY_PERMISSION", packageData = true),
                details,
            ),
            settingsAttempts(OemFamily.Generic, PermissionKind.Overlay),
        )
        assertEquals(
            listOf(
                SettingsAttempt(action = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS", packageData = true),
                SettingsAttempt(action = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),
                details,
            ),
            settingsAttempts(OemFamily.Generic, PermissionKind.Battery),
        )
        assertEquals(
            listOf(
                SettingsAttempt(action = "android.settings.APP_NOTIFICATION_SETTINGS", extrasPackageKey = "android.provider.extra.APP_PACKAGE"),
                details,
            ),
            settingsAttempts(OemFamily.Generic, PermissionKind.Notifications),
        )
        val xiaomiPopup = listOf(
            SettingsAttempt(
                action = "miui.intent.action.APP_PERM_EDITOR",
                componentPackage = "com.miui.securitycenter",
                componentClass = "com.miui.permcenter.permissions.PermissionsEditorActivity",
                extrasPackageKey = "extra_pkgname",
            ),
            details,
        )
        OemFamily.entries.forEach { family ->
            assertEquals(xiaomiPopup, settingsAttempts(family, PermissionKind.BackgroundPopup))
        }
    }

    @Test fun `autostart routes offer OEM component sequence before the generic details fallback`() {
        val expected = mapOf(
            OemFamily.Xiaomi to listOf(
                SettingsAttempt(componentPackage = "com.miui.securitycenter", componentClass = "com.miui.permcenter.autostart.AutoStartManagementActivity"),
                SettingsAttempt(
                    action = "miui.intent.action.APP_PERM_EDITOR",
                    componentPackage = "com.miui.securitycenter",
                    componentClass = "com.miui.permcenter.permissions.PermissionsEditorActivity",
                    extrasPackageKey = "extra_pkgname",
                ),
                appDetails(),
            ),
            OemFamily.Oppo to listOf(
                SettingsAttempt(componentPackage = "com.coloros.safecenter", componentClass = "com.coloros.safecenter.permission.startup.StartupAppListActivity"),
                SettingsAttempt(componentPackage = "com.oplus.safecenter", componentClass = "com.oplus.safecenter.startupapp.StartupAppListActivity"),
                SettingsAttempt(componentPackage = "com.oneplus.security", componentClass = "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity"),
                appDetails(),
            ),
            OemFamily.Vivo to listOf(
                SettingsAttempt(componentPackage = "com.vivo.permissionmanager", componentClass = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"),
                SettingsAttempt(componentPackage = "com.iqoo.secure", componentClass = "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager"),
                appDetails(),
            ),
            OemFamily.Huawei to listOf(
                SettingsAttempt(componentPackage = "com.huawei.systemmanager", componentClass = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"),
                SettingsAttempt(componentPackage = "com.huawei.systemmanager", componentClass = "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"),
                appDetails(),
            ),
            OemFamily.Generic to listOf(appDetails()),
        )

        expected.forEach { (family, attempts) ->
            assertEquals(family.name, attempts, settingsAttempts(family, PermissionKind.Autostart))
            assertTrue("${family.name} retains a final fallback", attempts.last().action == "android.settings.APPLICATION_DETAILS_SETTINGS")
        }
    }

    private fun appDetails() = SettingsAttempt(
        action = "android.settings.APPLICATION_DETAILS_SETTINGS",
        packageData = true,
    )
}
