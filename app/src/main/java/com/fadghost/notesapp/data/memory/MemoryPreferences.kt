package com.fadghost.notesapp.data.memory

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-secret memory settings (V3-DELIGHT §8 infra): the on/off toggle, the lifetime
 * memory-save counter that drives the 1st/10th/50th "Folio remembers the more you keep."
 * hero line, and the last vault checksum used to skip the app-start mirror rebuild when
 * nothing changed. DataStore, no secrets.
 */
private val Context.memorySettingsStore by preferencesDataStore(name = "memory_settings")

@Singleton
class MemoryPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val enabledKey = booleanPreferencesKey("memory_enabled")
    private val saveCountKey = intPreferencesKey("memory_save_count")
    private val checksumKey = stringPreferencesKey("memory_checksum")

    /** Default ON — memory is a headline feature; the editor still never writes silently. */
    val enabled: Flow<Boolean> = context.memorySettingsStore.data.map { it[enabledKey] ?: true }
    val saveCount: Flow<Int> = context.memorySettingsStore.data.map { it[saveCountKey] ?: 0 }

    suspend fun enabledNow(): Boolean = enabled.first()

    suspend fun setEnabled(value: Boolean) {
        context.memorySettingsStore.edit { it[enabledKey] = value }
    }

    /** Increment and return the new lifetime save count (drives the hero-line throttle). */
    suspend fun bumpSaveCount(by: Int): Int {
        var result = 0
        context.memorySettingsStore.edit {
            result = (it[saveCountKey] ?: 0) + by
            it[saveCountKey] = result
        }
        return result
    }

    suspend fun lastChecksum(): String =
        context.memorySettingsStore.data.map { it[checksumKey] ?: "" }.first()

    suspend fun setChecksum(value: String) {
        context.memorySettingsStore.edit { it[checksumKey] = value }
    }
}
