package dev.kigya.cutout.data

import android.app.Application
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.mockk.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class TelemetryPrivacyTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val analytics = mockk<FirebaseAnalytics>(relaxed = true)
    private val crashes = mockk<FirebaseCrashlytics>(relaxed = true)
    private val remote = mockk<FirebaseRemoteConfig>(relaxed = true)
    @Before fun setUp() {
        mockkStatic(FirebaseApp::class, FirebaseAnalytics::class, FirebaseCrashlytics::class, FirebaseRemoteConfig::class)
        every { FirebaseApp.getApps(any()) } returns listOf(mockk())
        every { FirebaseAnalytics.getInstance(any()) } returns analytics
        every { FirebaseCrashlytics.getInstance() } returns crashes
        every { FirebaseRemoteConfig.getInstance() } returns remote
    }
    @After fun tearDown() { unmockkAll() }
    @Test fun `native startup denies analytics and every advertising use before JS consent`() {
        AppTelemetry(app).applyConsent()
        verify { analytics.setAnalyticsCollectionEnabled(false) }
        verify { analytics.setConsent(match { consent -> consent.size == 4 && consent.values.all { it == FirebaseAnalytics.ConsentStatus.DENIED } }) }
        verify { crashes.setCrashlyticsCollectionEnabled(false); crashes.deleteUnsentReports() }
        verify(exactly = 0) { remote.fetchAndActivate() }
        AppTelemetry(app).event("cutout_started")
        verify(exactly = 0) { analytics.logEvent(any(), any()) }
    }
    @Test fun `analytics and crashes can be consented separately and withdrawal resets analytics`() {
        val telemetry = AppTelemetry(app)
        telemetry.update("light", true, false)
        verify { analytics.setAnalyticsCollectionEnabled(true) }
        verify { crashes.setCrashlyticsCollectionEnabled(false) }
        verify { analytics.setConsent(match { it[FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE] == FirebaseAnalytics.ConsentStatus.GRANTED && it.filterKeys { key -> key != FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE }.values.all { status -> status == FirebaseAnalytics.ConsentStatus.DENIED } }) }
        verify { remote.setDefaultsAsync(mapOf("review_min_exports" to 5L, "review_cooldown_days" to 90L)); remote.fetchAndActivate() }
        telemetry.update("system", false, true)
        verify { crashes.setCrashlyticsCollectionEnabled(true) }
        verify { analytics.resetAnalyticsData() }
        assertFalse(telemetry.analyticsEnabled)
        assertTrue(telemetry.crashEnabled)
    }
    @Test fun `consent is persisted while Firebase is absent without initializing telemetry services`() {
        every { FirebaseApp.getApps(any()) } returns emptyList()

        val telemetry = AppTelemetry(app)
        telemetry.update("dark", analytics = true, crashes = true)
        telemetry.event("screen_view", mapOf("screen_name" to "home"))

        val restored = AppTelemetry(app)
        assertEquals("dark", restored.theme)
        assertTrue(restored.analyticsEnabled)
        assertTrue(restored.crashEnabled)
        verify(exactly = 0) { FirebaseAnalytics.getInstance(any()) }
        verify(exactly = 0) { FirebaseCrashlytics.getInstance() }
        verify(exactly = 0) { FirebaseRemoteConfig.getInstance() }
    }
    @Test fun `allowlist rejects media paths PII arbitrary names and unsupported payload values`() {
        val telemetry = AppTelemetry(app)
        telemetry.update("system", true, true)
        telemetry.event("unapproved_event", mapOf("source" to "clipboard"))
        verify(exactly = 0) { analytics.logEvent(any(), any()) }
        telemetry.event("cutout_completed", mapOf(
            "source" to "clipboard", "duration_ms" to 123L, "enabled" to true,
            "path" to "/private/image.png", "email" to "person@example.com",
            "stage" to "private/photo", "result" to "x".repeat(41),
            "format" to null, "screen_name" to listOf("private"), "action" to "x".repeat(40),
        ))
        val params = slot<Bundle>()
        verify(exactly = 1) { analytics.logEvent("cutout_completed", capture(params)) }
        assertEquals(setOf("source", "duration_ms", "enabled", "action"), params.captured.keySet())
        assertEquals("clipboard", params.captured.getString("source"))
        assertEquals(123.0, params.captured.getDouble("duration_ms"), 0.0)
        assertEquals(1L, params.captured.getLong("enabled"))
        telemetry.event("bubble_changed", mapOf("enabled" to false))
        val disabled = slot<Bundle>()
        verify { analytics.logEvent("bubble_changed", capture(disabled)) }
        assertEquals(0L, disabled.captured.getLong("enabled"))
    }
}
