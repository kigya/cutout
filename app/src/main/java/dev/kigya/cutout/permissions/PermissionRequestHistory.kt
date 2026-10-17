package dev.kigya.cutout.permissions

import android.content.Context
import dev.kigya.cutout.domain.PermissionKind
import java.util.UUID

data class PermissionHistoryEntry(
    val deniedCount: Int,
    val permanentlyDenied: Boolean,
    val completedAttempts: Int,
)

/** Stores only permission-flow state; it never stores the requested content or settings-page data. */
class PermissionRequestHistory(context: Context) {
    private val preferences = context.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun begin(kind: PermissionKind): String = synchronized(lock) {
        val id = UUID.randomUUID().toString()
        check(preferences.edit().putString(activeKey(kind), id).commit())
        id
    }

    /** Consumes a matching request once. Unknown status completes the attempt but cannot count as denial. */
    fun complete(
        kind: PermissionKind,
        attemptId: String,
        status: GrantStatus,
        blocked: Boolean,
    ): PermissionHistoryEntry? = synchronized(lock) {
        if (preferences.getString(activeKey(kind), null) != attemptId) return@synchronized null

        if (status == GrantStatus.Unknown) {
            check(preferences.edit().remove(activeKey(kind)).commit()) { "Could not clear unknown permission request" }
            return@synchronized entry(kind)
        }

        val oldDenied = preferences.getInt(deniedKey(kind), 0)
        val denied = oldDenied + if (status == GrantStatus.Denied) 1 else 0
        val permanent = status == GrantStatus.Denied && blocked
        val completed = preferences.getInt(completedKey(kind), 0) + 1
        val saved = preferences.edit()
            .remove(activeKey(kind))
            .putInt(deniedKey(kind), denied)
            .putInt(completedKey(kind), completed)
            .putBoolean(permanentKey(kind), permanent)
            .commit()
        check(saved) { "Could not persist permission request result" }
        PermissionHistoryEntry(denied, permanent, completed)
    }

    fun entry(kind: PermissionKind): PermissionHistoryEntry = synchronized(lock) {
        PermissionHistoryEntry(
            deniedCount = preferences.getInt(deniedKey(kind), 0),
            permanentlyDenied = preferences.getBoolean(permanentKey(kind), false),
            completedAttempts = preferences.getInt(completedKey(kind), 0),
        )
    }

    private fun activeKey(kind: PermissionKind) = "permission_active_${kind.name}"
    private fun deniedKey(kind: PermissionKind) = "permission_denied_${kind.name}"
    private fun permanentKey(kind: PermissionKind) = "permission_blocked_${kind.name}"
    private fun completedKey(kind: PermissionKind) = "permission_completed_${kind.name}"

    private companion object {
        const val FILE = "cutout_permission_history"
        val lock = Any()
    }
}
