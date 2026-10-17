package dev.kigya.cutout.domain

enum class OemFamily {
    Xiaomi,
    Oppo,
    Vivo,
    Huawei,
    Generic,
}

enum class PermissionKind {
    Overlay,
    BackgroundPopup,
    Autostart,
    Battery,
    Notifications,
}

enum class PermissionNeed {
    RequiredForBubble,
    Optional,
}

fun permissionNeed(kind: PermissionKind): PermissionNeed =
    if (kind == PermissionKind.Overlay) PermissionNeed.RequiredForBubble else PermissionNeed.Optional

fun blocksInAppCutout(@Suppress("UNUSED_PARAMETER") kind: PermissionKind): Boolean = false

data class SettingsAttempt(
    val action: String? = null,
    val componentPackage: String? = null,
    val componentClass: String? = null,
    val packageData: Boolean = false,
    val extrasPackageKey: String? = null,
)

fun oemFamily(manufacturer: String, brand: String = ""): OemFamily {
    val value = "$manufacturer $brand".lowercase()
    return when {
        listOf("xiaomi", "redmi", "poco", "blackshark").any(value::contains) -> OemFamily.Xiaomi
        listOf("oppo", "realme", "oneplus").any(value::contains) -> OemFamily.Oppo
        listOf("vivo", "iqoo").any(value::contains) -> OemFamily.Vivo
        listOf("huawei", "honor").any(value::contains) -> OemFamily.Huawei
        else -> OemFamily.Generic
    }
}

fun visiblePermissions(family: OemFamily): List<PermissionKind> = when (family) {
    OemFamily.Xiaomi -> listOf(
        PermissionKind.Overlay,
        PermissionKind.BackgroundPopup,
        PermissionKind.Autostart,
        PermissionKind.Battery,
        PermissionKind.Notifications,
    )
    OemFamily.Oppo, OemFamily.Vivo, OemFamily.Huawei -> listOf(
        PermissionKind.Overlay,
        PermissionKind.Autostart,
        PermissionKind.Battery,
        PermissionKind.Notifications,
    )
    OemFamily.Generic -> listOf(
        PermissionKind.Overlay,
        PermissionKind.Battery,
        PermissionKind.Notifications,
    )
}

fun settingsAttempts(family: OemFamily, kind: PermissionKind): List<SettingsAttempt> = when (kind) {
    PermissionKind.Overlay -> listOf(
        SettingsAttempt(
            action = "android.settings.action.MANAGE_OVERLAY_PERMISSION",
            packageData = true,
        ),
        appDetails(),
    )
    PermissionKind.BackgroundPopup -> xiaomiPermissionEditor() + appDetails()
    PermissionKind.Autostart -> autostartAttempts(family)
    PermissionKind.Battery -> listOf(
        SettingsAttempt(
            action = "android.settings.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
            packageData = true,
        ),
        SettingsAttempt(action = "android.settings.IGNORE_BATTERY_OPTIMIZATION_SETTINGS"),
        appDetails(),
    )
    PermissionKind.Notifications -> listOf(
        SettingsAttempt(
            action = "android.settings.APP_NOTIFICATION_SETTINGS",
            extrasPackageKey = "android.provider.extra.APP_PACKAGE",
        ),
        appDetails(),
    )
}

private fun autostartAttempts(family: OemFamily): List<SettingsAttempt> = when (family) {
    OemFamily.Xiaomi -> listOf(
        SettingsAttempt(
            componentPackage = "com.miui.securitycenter",
            componentClass = "com.miui.permcenter.autostart.AutoStartManagementActivity",
        ),
    ) + xiaomiPermissionEditor() + appDetails()
    OemFamily.Oppo -> listOf(
        SettingsAttempt(
            componentPackage = "com.coloros.safecenter",
            componentClass = "com.coloros.safecenter.permission.startup.StartupAppListActivity",
        ),
        SettingsAttempt(
            componentPackage = "com.oplus.safecenter",
            componentClass = "com.oplus.safecenter.startupapp.StartupAppListActivity",
        ),
        SettingsAttempt(
            componentPackage = "com.oneplus.security",
            componentClass = "com.oneplus.security.chainlaunch.view.ChainLaunchAppListActivity",
        ),
        appDetails(),
    )
    OemFamily.Vivo -> listOf(
        SettingsAttempt(
            componentPackage = "com.vivo.permissionmanager",
            componentClass = "com.vivo.permissionmanager.activity.BgStartUpManagerActivity",
        ),
        SettingsAttempt(
            componentPackage = "com.iqoo.secure",
            componentClass = "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager",
        ),
        appDetails(),
    )
    OemFamily.Huawei -> listOf(
        SettingsAttempt(
            componentPackage = "com.huawei.systemmanager",
            componentClass = "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity",
        ),
        SettingsAttempt(
            componentPackage = "com.huawei.systemmanager",
            componentClass = "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity",
        ),
        appDetails(),
    )
    OemFamily.Generic -> listOf(appDetails())
}

private fun xiaomiPermissionEditor(): List<SettingsAttempt> = listOf(
    SettingsAttempt(
        action = "miui.intent.action.APP_PERM_EDITOR",
        componentPackage = "com.miui.securitycenter",
        componentClass = "com.miui.permcenter.permissions.PermissionsEditorActivity",
        extrasPackageKey = "extra_pkgname",
    ),
)

private fun appDetails(): SettingsAttempt = SettingsAttempt(
    action = "android.settings.APPLICATION_DETAILS_SETTINGS",
    packageData = true,
)
