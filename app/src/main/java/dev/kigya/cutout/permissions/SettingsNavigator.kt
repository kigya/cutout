package dev.kigya.cutout.permissions

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import dev.kigya.cutout.domain.PermissionKind
import dev.kigya.cutout.domain.SettingsAttempt
import dev.kigya.cutout.domain.settingsAttempts
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SettingsNavigator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val permissions: PermissionCenter,
) {
    fun open(kind: PermissionKind): Boolean {
        val attempts = settingsAttempts(permissions.family(), kind)
        return attempts.any(::start)
    }

    private fun start(attempt: SettingsAttempt): Boolean {
        val intent = Intent().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (attempt.componentPackage != null && attempt.componentClass != null) {
            intent.setClassName(attempt.componentPackage, attempt.componentClass)
            if (attempt.action != null) intent.action = attempt.action
        } else if (attempt.action != null) {
            intent.action = attempt.action
        } else {
            return false
        }
        if (attempt.packageData) {
            intent.data = Uri.parse("package:${context.packageName}")
        }
        if (attempt.extrasPackageKey != null) {
            intent.putExtra(attempt.extrasPackageKey, context.packageName)
        }
        return try {
            context.startActivity(intent)
            true
        } catch (error: ActivityNotFoundException) {
            Log.w(TAG, "settings activity missing", error)
            false
        } catch (error: SecurityException) {
            Log.w(TAG, "settings activity blocked", error)
            false
        }
    }

    private companion object {
        const val TAG = "SettingsNavigator"
    }
}
