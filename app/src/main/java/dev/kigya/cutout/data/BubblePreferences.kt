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

data class BubbleSpot(val x: Float, val y: Float)

@Singleton
class BubblePreferences @Inject constructor(
    @ApplicationContext private val context: Context,
) {
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
        val ENABLED = booleanPreferencesKey("bubble_enabled")
        val SPOT_X = floatPreferencesKey("bubble_x")
        val SPOT_Y = floatPreferencesKey("bubble_y")
    }
}
