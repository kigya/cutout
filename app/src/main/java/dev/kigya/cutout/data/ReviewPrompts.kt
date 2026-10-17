package dev.kigya.cutout.data

import android.app.Activity
import android.content.Context
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import dev.kigya.cutout.domain.reviewEligible

/** Best effort platform prompt after successful exports. Never gates the export result. */
class ReviewPrompts(
    context: Context,
    private val manager: () -> ReviewManager = { ReviewManagerFactory.create(context) },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val preferences = context.getSharedPreferences("cutout_review", Context.MODE_PRIVATE)
    private val appContext = context.applicationContext
    fun exportSucceeded(activity: Activity?) { runCatching { recordAndRequest(activity) } }

    private fun recordAndRequest(activity: Activity?) {
        val count = (preferences.getInt("exports", 0).toLong() + 1).coerceIn(0, Int.MAX_VALUE.toLong()).toInt()
        preferences.edit().putInt("exports", count).apply()
        if (activity == null || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) return
        var minimum = 5L
        var cooldown = 90L
        if (AppTelemetry(appContext).analyticsEnabled && FirebaseApp.getApps(appContext).isNotEmpty()) {
            val remote = FirebaseRemoteConfig.getInstance()
            minimum = remote.getLong("review_min_exports")
            cooldown = remote.getLong("review_cooldown_days")
        }
        val instant = now()
        if (!reviewEligible(count, instant, preferences.getLong("attempt", 0), minimum, cooldown)) return
        // Persist before calling the SDK, including unavailable-store failures: no retry loop.
        preferences.edit().putLong("attempt", instant).apply()
        runCatching {
            val reviews = manager()
            reviews.requestReviewFlow().addOnSuccessListener { info ->
                if (!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()) {
                    runCatching { reviews.launchReviewFlow(activity, info) }
                }
            }
        }
    }
}
