package dev.kigya.cutout.permissions

import android.app.AppOpsManager
import android.content.Context
import android.os.Build
import android.os.PowerManager
import android.os.Process
import android.provider.Settings
import androidx.core.content.ContextCompat
import dev.kigya.cutout.domain.OemFamily
import dev.kigya.cutout.domain.PermissionKind
import dev.kigya.cutout.domain.oemFamily
import dev.kigya.cutout.domain.visiblePermissions
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

enum class GrantStatus {
    Granted,
    Denied,
    Unknown,
}

data class PermissionSnapshot(
    val kind: PermissionKind,
    val status: GrantStatus,
)

@Singleton
class PermissionCenter @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    fun family(): OemFamily = oemFamily(Build.MANUFACTURER, Build.BRAND)

    fun snapshot(): List<PermissionSnapshot> {
        val family = family()
        return visiblePermissions(family).map { kind ->
            PermissionSnapshot(kind, status(kind, family))
        }
    }

    private fun status(kind: PermissionKind, family: OemFamily): GrantStatus = when (kind) {
        PermissionKind.Overlay ->
            if (Settings.canDrawOverlays(context)) GrantStatus.Granted else GrantStatus.Denied
        PermissionKind.Notifications -> notifications()
        PermissionKind.Battery ->
            if (power().isIgnoringBatteryOptimizations(context.packageName)) {
                GrantStatus.Granted
            } else {
                GrantStatus.Denied
            }
        PermissionKind.BackgroundPopup ->
            if (family == OemFamily.Xiaomi) appOp(OP_BACKGROUND_START) else GrantStatus.Unknown
        PermissionKind.Autostart ->
            if (family == OemFamily.Xiaomi) appOp(OP_AUTOSTART) else GrantStatus.Unknown
    }

    private fun notifications(): GrantStatus {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return GrantStatus.Granted
        val granted = ContextCompat.checkSelfPermission(
            context,
            android.Manifest.permission.POST_NOTIFICATIONS,
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        return if (granted) GrantStatus.Granted else GrantStatus.Denied
    }

    private fun appOp(op: Int): GrantStatus {
        return try {
            val manager = context.getSystemService(AppOpsManager::class.java) ?: return GrantStatus.Unknown
            val method = AppOpsManager::class.java.getMethod(
                "checkOpNoThrow",
                Int::class.javaPrimitiveType,
                Int::class.javaPrimitiveType,
                String::class.java,
            )
            val mode = method.invoke(manager, op, Process.myUid(), context.packageName) as Int
            if (mode == AppOpsManager.MODE_ALLOWED) GrantStatus.Granted else GrantStatus.Denied
        } catch (_: ReflectiveOperationException) {
            GrantStatus.Unknown
        }
    }

    private fun power(): PowerManager = context.getSystemService(PowerManager::class.java)

    private companion object {
        const val OP_AUTOSTART = 10008
        const val OP_BACKGROUND_START = 10021
    }
}
