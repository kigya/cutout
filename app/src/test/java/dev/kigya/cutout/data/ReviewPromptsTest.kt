package dev.kigya.cutout.data

import android.app.Activity
import android.app.Application
import android.content.Context
import com.google.android.gms.tasks.TaskCompletionSource
import com.google.android.gms.tasks.Tasks
import com.google.android.play.core.review.ReviewInfo
import com.google.android.play.core.review.ReviewManager
import com.google.android.play.core.review.ReviewManagerFactory
import com.google.firebase.FirebaseApp
import com.google.firebase.remoteconfig.FirebaseRemoteConfig
import io.mockk.*
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class ReviewPromptsTest {
    private val app: Application get() = RuntimeEnvironment.getApplication()
    private val prefs get() = app.getSharedPreferences("cutout_review", Context.MODE_PRIVATE)
    private fun activity() = mockk<Activity>().also { a ->
        every { a.isFinishing } returns false
        every { a.isDestroyed } returns false
        every { a.hasWindowFocus() } returns true
    }
    @After fun tearDown() { unmockkAll() }
    @Test fun `only fifth successful foreground export requests a neutral platform review`() {
        val manager = mockk<ReviewManager>(relaxed = true)
        val info = mockk<ReviewInfo>()
        every { manager.requestReviewFlow() } returns Tasks.forResult(info)
        val a = activity()
        val prompts = ReviewPrompts(app, { manager }, { 1000L })
        repeat(4) { prompts.exportSucceeded(a) }
        verify(exactly = 0) { manager.requestReviewFlow() }
        prompts.exportSucceeded(a)
        ShadowLooper.idleMainLooper()
        verify(exactly = 1) { manager.launchReviewFlow(a, info) }
        assertEquals(5, prefs.getInt("exports", 0))
        prompts.exportSucceeded(a)
        ShadowLooper.idleMainLooper()
        verify(exactly = 1) { manager.requestReviewFlow() }
    }
    @Test fun `background exports count but cannot open a review dialog`() {
        val manager = mockk<ReviewManager>(relaxed = true)
        val prompts = ReviewPrompts(app, { manager }, { 1000L })
        prefs.edit().putInt("exports", 4).apply()
        prompts.exportSucceeded(null)
        val a = activity()
        every { a.isFinishing } returns true
        prompts.exportSucceeded(a)
        every { a.isFinishing } returns false
        every { a.isDestroyed } returns true
        prompts.exportSucceeded(a)
        every { a.isDestroyed } returns false
        every { a.hasWindowFocus() } returns false
        prompts.exportSucceeded(a)
        assertEquals(8, prefs.getInt("exports", 0))
        verify(exactly = 0) { manager.requestReviewFlow() }
        assertEquals(0, prefs.getLong("attempt", 0))
    }
    @Test fun `delayed platform response cannot show after activity loses focus`() {
        val manager = mockk<ReviewManager>(relaxed = true)
        val pending = TaskCompletionSource<ReviewInfo>()
        every { manager.requestReviewFlow() } returns pending.task
        val a = activity()
        prefs.edit().putInt("exports", 4).apply()
        ReviewPrompts(app, { manager }, { 1000L }).exportSucceeded(a)
        every { a.hasWindowFocus() } returns false
        pending.setResult(mockk())
        ShadowLooper.idleMainLooper()
        verify(exactly = 0) { manager.launchReviewFlow(any(), any()) }
    }
    @Test fun `unavailable store never fails export or creates repeated prompt attempts`() {
        var creations = 0
        val prompts = ReviewPrompts(app, { creations++; error("store unavailable") }, { 1000L })
        prefs.edit().putInt("exports", Int.MAX_VALUE).apply()
        repeat(2) { prompts.exportSucceeded(activity()) }
        assertEquals(1, creations)
        assertEquals(Int.MAX_VALUE, prefs.getInt("exports", 0))
        assertEquals(1000L, prefs.getLong("attempt", 0))
    }
    @Test fun `remote review thresholds require analytics consent and a configured Firebase app`() {
        mockkStatic(FirebaseApp::class, FirebaseRemoteConfig::class)
        val remote = mockk<FirebaseRemoteConfig>()
        every { FirebaseRemoteConfig.getInstance() } returns remote
        every { remote.getLong("review_min_exports") } returns 3L
        every { remote.getLong("review_cooldown_days") } returns 30L
        val manager = mockk<ReviewManager>(relaxed = true)
        every { manager.requestReviewFlow() } returns Tasks.forException(IllegalStateException("no store"))
        val telemetry = app.getSharedPreferences("cutout_preferences", Context.MODE_PRIVATE)
        telemetry.edit().putBoolean("analytics", true).apply()
        every { FirebaseApp.getApps(any()) } returns emptyList()
        val prompts = ReviewPrompts(app, { manager }, { 1000L })
        prompts.exportSucceeded(activity())
        verify(exactly = 0) { remote.getLong(any()) }
        every { FirebaseApp.getApps(any()) } returns listOf(mockk())
        prompts.exportSucceeded(activity())
        verify(exactly = 0) { manager.requestReviewFlow() }
        prompts.exportSucceeded(activity())
        verify(exactly = 1) { manager.requestReviewFlow() }
        assertEquals(1000L, prefs.getLong("attempt", 0))
    }
    @Test fun `default platform manager and clock do not propagate a launch failure into export`() {
        mockkStatic(ReviewManagerFactory::class)
        val manager = mockk<ReviewManager>()
        every { ReviewManagerFactory.create(app) } returns manager
        every { manager.requestReviewFlow() } returns Tasks.forResult(mockk())
        every { manager.launchReviewFlow(any(), any()) } throws IllegalStateException("store disconnected")
        prefs.edit().putInt("exports", 4).apply()
        ReviewPrompts(app).exportSucceeded(activity())
        ShadowLooper.idleMainLooper()
        verify(exactly = 1) { manager.launchReviewFlow(any(), any()) }
        assertTrue(prefs.getLong("attempt", 0) > 0)
    }
    @Test fun `delayed response does not show a dialog after finishing or destruction`() {
        for (finishing in listOf(true, false)) {
            prefs.edit().putInt("exports", 4).putLong("attempt", 0).apply()
            val a = activity()
            val manager = mockk<ReviewManager>(relaxed = true)
            val pending = TaskCompletionSource<ReviewInfo>()
            every { manager.requestReviewFlow() } returns pending.task
            ReviewPrompts(app, { manager }, { 1000L }).exportSucceeded(a)
            if (finishing) every { a.isFinishing } returns true else every { a.isDestroyed } returns true
            pending.setResult(mockk())
            ShadowLooper.idleMainLooper()
            verify(exactly = 0) { manager.launchReviewFlow(any(), any()) }
        }
    }

}
