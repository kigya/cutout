package dev.kigya.cutout.data

import android.content.Context
import android.os.Bundle
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics
import com.google.firebase.remoteconfig.FirebaseRemoteConfig

/** Optional diagnostics. Media, clipboard values and file paths never enter this boundary. */
class AppTelemetry(context: Context) {
    private val appContext = context.applicationContext
    private val preferences = context.getSharedPreferences("cutout_preferences", Context.MODE_PRIVATE)
    init {
        if (!preferences.getBoolean(DIAGNOSTICS_BASELINE, false)) {
            preferences.edit()
                .putBoolean("analytics", true)
                .putBoolean("crashes", true)
                .putBoolean(DIAGNOSTICS_NOTICE_PENDING, true)
                .putBoolean(DIAGNOSTICS_BASELINE, true)
                .commit()
        }
    }
    val analyticsEnabled get() = preferences.getBoolean("analytics", false)
    val crashEnabled get() = preferences.getBoolean("crashes", false)
    val theme get() = preferences.getString("theme", "system") ?: "system"
    val diagnosticsNoticePending get() = preferences.getBoolean(DIAGNOSTICS_NOTICE_PENDING, false)
    private val configured get() = FirebaseApp.getApps(appContext).isNotEmpty()

    fun applyConsent() {
        if (!configured) return
        val analytics = FirebaseAnalytics.getInstance(appContext)
        analytics.setConsent(mapOf(
            FirebaseAnalytics.ConsentType.ANALYTICS_STORAGE to if (analyticsEnabled) FirebaseAnalytics.ConsentStatus.GRANTED else FirebaseAnalytics.ConsentStatus.DENIED,
            FirebaseAnalytics.ConsentType.AD_STORAGE to FirebaseAnalytics.ConsentStatus.DENIED,
            FirebaseAnalytics.ConsentType.AD_USER_DATA to FirebaseAnalytics.ConsentStatus.DENIED,
            FirebaseAnalytics.ConsentType.AD_PERSONALIZATION to FirebaseAnalytics.ConsentStatus.DENIED,
        ))
        analytics.setAnalyticsCollectionEnabled(analyticsEnabled)
        val crashes = FirebaseCrashlytics.getInstance()
        crashes.setCrashlyticsCollectionEnabled(crashEnabled)
        if (!crashEnabled) crashes.deleteUnsentReports()
        if (analyticsEnabled) {
            FirebaseRemoteConfig.getInstance().apply {
                setDefaultsAsync(mapOf("review_min_exports" to 5L, "review_cooldown_days" to 90L))
                fetchAndActivate()
            }
        }
    }

    fun update(theme: String, analytics: Boolean, crashes: Boolean) {
        val withdrawing = analyticsEnabled && !analytics
        preferences.edit().putString("theme", theme.takeIf { it in setOf("system", "light", "dark") } ?: "system")
            .putBoolean("analytics", analytics).putBoolean("crashes", crashes).apply()
        applyConsent()
        if (withdrawing && configured) FirebaseAnalytics.getInstance(appContext).resetAnalyticsData()
    }

    fun acknowledgeDiagnosticsNotice(): Boolean =
        preferences.edit().putBoolean(DIAGNOSTICS_NOTICE_PENDING, false).commit()

    fun event(name: String, values: Map<String, Any?> = emptyMap()) {
        if (!analyticsEnabled || !configured || name !in EVENTS) return
        val parameters = Bundle()
        values.filterKeys { it in FIELDS }.forEach { (key, value) ->
            when (value) {
                is String -> if (value.length <= 40 && value.matches(Regex("[a-zA-Z0-9_.-]+"))) parameters.putString(key, value)
                is Number -> parameters.putDouble(key, value.toDouble())
                is Boolean -> parameters.putLong(key, if (value) 1 else 0)
            }
        }
        FirebaseAnalytics.getInstance(appContext).logEvent(name, parameters)
    }

    companion object {
        private const val DIAGNOSTICS_BASELINE = "diagnostics_baseline_initialized"
        private const val DIAGNOSTICS_NOTICE_PENDING = "diagnostics_notice_pending"
        val EVENTS = setOf("screen_view", "screen_duration", "cutout_started", "cutout_completed", "cutout_failed", "cutout_cancelled", "editor_action", "export_completed", "export_failed", "bubble_changed", "permission_result", "locale_changed")
        val FIELDS = setOf("screen_name", "duration_ms", "source", "surface", "format", "action", "result", "permission", "enabled", "locale", "stage")
    }
}
