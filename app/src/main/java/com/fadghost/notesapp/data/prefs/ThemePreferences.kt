package com.fadghost.notesapp.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * User-selectable theme mode (PLAN.md §9). Light/Dark shipped in M0; AMOLED + Grey
 * added in M6 with the token system already in place.
 */
enum class ThemeMode { LIGHT, DARK, AMOLED, GREY, SYSTEM }

private val Context.dataStore by preferencesDataStore(name = "settings")

@Singleton
class ThemePreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val themeKey = stringPreferencesKey("theme_mode")
    private val accentKey = intPreferencesKey("accent_index")
    private val reduceMotionKey = booleanPreferencesKey("reduce_motion")
    private val lastSeenVersionKey = stringPreferencesKey("last_seen_version")
    private val textScaleKey = floatPreferencesKey("text_scale")

    val themeMode: Flow<ThemeMode> = context.dataStore.data.map { prefs ->
        runCatching { ThemeMode.valueOf(prefs[themeKey] ?: ThemeMode.SYSTEM.name) }
            .getOrDefault(ThemeMode.SYSTEM)
    }

    suspend fun setThemeMode(mode: ThemeMode) {
        context.dataStore.edit { it[themeKey] = mode.name }
    }

    /** Accent picker index (PLAN.md §9). -1 (default) means "use the theme accent". */
    val accentIndex: Flow<Int> = context.dataStore.data.map { prefs ->
        prefs[accentKey] ?: -1
    }

    suspend fun setAccentIndex(index: Int) {
        context.dataStore.edit { it[accentKey] = index }
    }

    /** In-app reduce-motion toggle (PLAN.md §10). ORed with the system animator scale. */
    val reduceMotion: Flow<Boolean> = context.dataStore.data.map { prefs ->
        prefs[reduceMotionKey] ?: false
    }

    suspend fun setReduceMotion(enabled: Boolean) {
        context.dataStore.edit { it[reduceMotionKey] = enabled }
    }

    /**
     * In-app text scale (IDEAS #89), multiplied with the system font scale via the
     * density composition local. Clamped so nothing can render unusably tiny/huge.
     */
    val textScale: Flow<Float> = context.dataStore.data.map { prefs ->
        (prefs[textScaleKey] ?: 1f).coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX)
    }

    suspend fun setTextScale(scale: Float) {
        context.dataStore.edit { it[textScaleKey] = scale.coerceIn(TEXT_SCALE_MIN, TEXT_SCALE_MAX) }
    }

    /** Last versionName the "What's new" sheet was shown for (PLAN.md §13). */
    val lastSeenVersion: Flow<String> = context.dataStore.data.map { prefs ->
        prefs[lastSeenVersionKey] ?: ""
    }

    suspend fun setLastSeenVersion(version: String) {
        context.dataStore.edit { it[lastSeenVersionKey] = version }
    }

    companion object {
        const val TEXT_SCALE_MIN = 0.85f
        const val TEXT_SCALE_MAX = 1.3f
    }
}
