package dev.kigya.cutout.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.eraseStore by preferencesDataStore(name = "erase")

data class BubbleAppearance(
    val sizeDp: Int = 64,
    val opacity: Float = 0.7f,
    val shrinkWhenIdle: Boolean = false,
    val shrinkToDot: Boolean = false,
)

data class BubbleSpot(val x: Float, val y: Float)

@Singleton
class BubblePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val onboardingCompleted: Flow<Boolean> = context.eraseStore.data.map { it[ONBOARDING] ?: false }

    suspend fun completeOnboarding() {
        context.eraseStore.edit { it[ONBOARDING] = true }
    }

    val appearance: Flow<BubbleAppearance> = context.eraseStore.data.map { values ->
        BubbleAppearance(
            sizeDp = (values[SIZE] ?: 64f).toInt().coerceIn(48, 88),
            opacity = (values[OPACITY] ?: 0.7f).takeIf { it.isFinite() }?.coerceIn(0.2f, 1f) ?: 0.7f,
            shrinkWhenIdle = values[SHRINK] ?: false,
            shrinkToDot = values[DOT] ?: false,
        )
    }

    suspend fun setAppearance(value: BubbleAppearance) {
        require(value.opacity.isFinite())
        context.eraseStore.edit {
            it[SIZE] = (48 + kotlin.math.round((value.sizeDp.coerceIn(48, 88) - 48) / 4f).toInt() * 4).toFloat()
            it[OPACITY] = (kotlin.math.round(value.opacity.coerceIn(0.2f, 1f) * 20) / 20)
            it[SHRINK] = value.shrinkWhenIdle
            it[DOT] = value.shrinkToDot
        }
    }

    val enabled: Flow<Boolean> = context.eraseStore.data.map { preferences ->
        preferences[ENABLED] ?: false
    }

    val spot: Flow<BubbleSpot?> = context.eraseStore.data.map { preferences ->
        val x = preferences[SPOT_X] ?: return@map null
        val y = preferences[SPOT_Y] ?: return@map null
        BubbleSpot(x, y)
    }

    suspend fun setEnabled(enabled: Boolean) {
        context.eraseStore.edit { preferences -> preferences[ENABLED] = enabled }
    }

    suspend fun setSpot(x: Float, y: Float) {
        context.eraseStore.edit { preferences ->
            preferences[SPOT_X] = x
            preferences[SPOT_Y] = y
        }
    }

    private companion object {
        val ONBOARDING = booleanPreferencesKey("onboarding_completed")
        val SIZE = floatPreferencesKey("bubble_size_dp")
        val OPACITY = floatPreferencesKey("bubble_opacity")
        val SHRINK = booleanPreferencesKey("bubble_shrink_idle")
        val DOT = booleanPreferencesKey("bubble_shrink_dot")
        val ENABLED = booleanPreferencesKey("bubble_enabled")
        val SPOT_X = floatPreferencesKey("bubble_x")
        val SPOT_Y = floatPreferencesKey("bubble_y")
    }
}
