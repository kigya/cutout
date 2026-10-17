package dev.kigya.cutout.data

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import io.mockk.every
import io.mockk.mockkStatic
import io.mockk.unmockkStatic
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
class BubblePreferencesTest {
    @Test fun `immediately available store preserves coordinates and propagates failed writes`() = runBlocking<Unit> {
        val type = Class.forName("dev.kigya.cutout.data.BubblePreferencesKt")
        val data = MutableStateFlow<Preferences>(emptyPreferences())
        var failure: Exception? = null
        val store = object : DataStore<Preferences> {
            override val data = data
            override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences {
                failure?.let { throw it }
                return transform(data.value).also { data.value = it }
            }
        }
        mockkStatic(type.kotlin)
        try {
            every { ReflectionHelpers.callStaticMethod<DataStore<Preferences>>(
                type, "access\$getEraseStore",
                ReflectionHelpers.ClassParameter.from(Context::class.java, RuntimeEnvironment.getApplication()),
            ) } returns store
            val preferences = BubblePreferences(RuntimeEnvironment.getApplication())
            preferences.setEnabled(true)
            assertTrue(preferences.enabled.first())
            preferences.setSpot(0.8f, 0.1f)
            assertEquals(BubbleSpot(0.8f, 0.1f), preferences.spot.first())
            failure = java.io.IOException("disk full")
            try {
                preferences.setEnabled(false)
                fail("A failed write must not appear successful")
            } catch (expected: java.io.IOException) { assertEquals("disk full", expected.message) }
            assertTrue(preferences.enabled.first())
        } finally { unmockkStatic(type.kotlin) }
    }
    @Test fun `new install leaves Bubble off and coordinates survive repository recreation`() = runBlocking<Unit> {
        withTimeout(5000) {
            val app = RuntimeEnvironment.getApplication()
            val preferences = BubblePreferences(app)
            assertFalse(preferences.enabled.first())
            assertNull(preferences.spot.first())
            preferences.setEnabled(true)
            assertTrue(BubblePreferences(app).enabled.first())
            preferences.setEnabled(false)
            assertFalse(preferences.enabled.first())
            preferences.setSpot(0.2f, 0.7f)
            assertEquals(BubbleSpot(0.2f, 0.7f), BubblePreferences(app).spot.first())

            // An interrupted/older persisted coordinate pair must not restore a half-position.
            val store = ReflectionHelpers.callStaticMethod<DataStore<Preferences>>(
                Class.forName("dev.kigya.cutout.data.BubblePreferencesKt"), "getEraseStore",
                ReflectionHelpers.ClassParameter.from(Context::class.java, app),
            )
            store.edit { it.remove(floatPreferencesKey("bubble_y")) }
            assertNull(preferences.spot.first())
        }
    }
}
