package dev.kigya.cutout.permissions

import android.app.Application
import android.content.Context
import dev.kigya.cutout.domain.PermissionKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class PermissionRequestHistoryTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()

    @Before fun clearHistory() {
        app.getSharedPreferences("cutout_permission_history", Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test fun `only a matching live denied request is counted once and survives recreation`() {
        val history = PermissionRequestHistory(app)
        val attempt = history.begin(PermissionKind.Overlay)

        val completed = history.complete(PermissionKind.Overlay, attempt, GrantStatus.Denied, blocked = true)
        assertEquals(PermissionHistoryEntry(deniedCount = 1, permanentlyDenied = true, completedAttempts = 1), completed)
        assertNull("duplicate callback must not count twice", history.complete(PermissionKind.Overlay, attempt, GrantStatus.Denied, blocked = true))
        assertEquals(completed, PermissionRequestHistory(app).entry(PermissionKind.Overlay))
    }

    @Test fun `unknown status is consumed without recording a denial and grant clears blocked state`() {
        val history = PermissionRequestHistory(app)
        val unknown = history.begin(PermissionKind.Notifications)
        assertEquals(
            PermissionHistoryEntry(deniedCount = 0, permanentlyDenied = false, completedAttempts = 0),
            history.complete(PermissionKind.Notifications, unknown, GrantStatus.Unknown, blocked = true),
        )

        val denied = history.begin(PermissionKind.Notifications)
        history.complete(PermissionKind.Notifications, denied, GrantStatus.Denied, blocked = true)
        val granted = history.begin(PermissionKind.Notifications)
        assertEquals(
            PermissionHistoryEntry(deniedCount = 1, permanentlyDenied = false, completedAttempts = 2),
            history.complete(PermissionKind.Notifications, granted, GrantStatus.Granted, blocked = false),
        )
        assertFalse(history.entry(PermissionKind.Notifications).permanentlyDenied)
        assertTrue(history.entry(PermissionKind.Notifications).deniedCount == 1)
    }

    @Test fun `a different or stale attempt id cannot consume the active request`() {
        val history = PermissionRequestHistory(app)
        val active = history.begin(PermissionKind.Battery)
        assertNull(history.complete(PermissionKind.Battery, "stale", GrantStatus.Denied, blocked = true))
        assertEquals(
            PermissionHistoryEntry(deniedCount = 1, permanentlyDenied = false, completedAttempts = 1),
            history.complete(PermissionKind.Battery, active, GrantStatus.Denied, blocked = false),
        )
    }
}
