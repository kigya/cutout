package dev.kigya.cutout.permissions

import android.app.Application
import android.content.Context
import dev.kigya.cutout.domain.PermissionKind
import io.mockk.every
import io.mockk.mockk
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

    @Test fun `begin fails closed when the active request cannot be persisted`() {
        val (context, preferences, editor) = failingPreferences()
        every { preferences.edit() } returns editor
        every { editor.putString(any(), any()) } returns editor
        every { editor.commit() } returns false

        val error = runCatching { PermissionRequestHistory(context).begin(PermissionKind.Overlay) }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
    }

    @Test fun `unknown completion reports a failed active request removal`() {
        val (context, preferences, editor) = failingPreferences()
        val kind = PermissionKind.Notifications
        every { preferences.getString("permission_active_${kind.name}", null) } returns "attempt"
        every { preferences.edit() } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.commit() } returns false

        val error = runCatching {
            PermissionRequestHistory(context).complete(kind, "attempt", GrantStatus.Unknown, blocked = false)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("Could not clear unknown permission request", error?.message)
    }

    @Test fun `known completion reports a failed result write`() {
        val (context, preferences, editor) = failingPreferences()
        val kind = PermissionKind.Battery
        every { preferences.getString("permission_active_${kind.name}", null) } returns "attempt"
        every { preferences.getInt(any(), any()) } returns 0
        every { preferences.edit() } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.putInt(any(), any()) } returns editor
        every { editor.putBoolean(any(), any()) } returns editor
        every { editor.commit() } returns false

        val error = runCatching {
            PermissionRequestHistory(context).complete(kind, "attempt", GrantStatus.Denied, blocked = true)
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertEquals("Could not persist permission request result", error?.message)
    }

    private fun failingPreferences(): Triple<Context, android.content.SharedPreferences, android.content.SharedPreferences.Editor> {
        val context = mockk<Context>()
        val preferences = mockk<android.content.SharedPreferences>()
        val editor = mockk<android.content.SharedPreferences.Editor>()
        every { context.applicationContext } returns context
        every { context.getSharedPreferences("cutout_permission_history", Context.MODE_PRIVATE) } returns preferences
        return Triple(context, preferences, editor)
    }
}
