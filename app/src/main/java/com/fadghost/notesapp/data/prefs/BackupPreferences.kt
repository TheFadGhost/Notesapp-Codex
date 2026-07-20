package com.fadghost.notesapp.data.prefs

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

private val Context.backupPrefsStore by preferencesDataStore(name = "backup_prefs")

/**
 * Backup bookkeeping (IDEAS #83): when the last successful export happened, so the
 * Settings card can say "Last backup: 12 days ago" and nudge when it goes stale.
 * Deliberately NOT part of the backup ZIP — it describes this device's habit,
 * not the user's data.
 */
@Singleton
class BackupPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val lastBackupAtKey = longPreferencesKey("last_backup_at")

    /** Epoch millis of the last successful export; 0 = never backed up. */
    val lastBackupAt: Flow<Long> = context.backupPrefsStore.data.map { it[lastBackupAtKey] ?: 0L }

    suspend fun markBackupDone(now: Long = System.currentTimeMillis()) {
        context.backupPrefsStore.edit { it[lastBackupAtKey] = now }
    }

    companion object {
        /** A backup older than this is "stale" and earns the amber nudge. */
        const val STALE_AFTER_DAYS = 14L

        /** Human line for the settings card. Pure for JVM tests. */
        fun describe(lastBackupAt: Long, now: Long): String {
            if (lastBackupAt <= 0L) return "Never backed up yet"
            val days = ((now - lastBackupAt) / 86_400_000L).coerceAtLeast(0)
            return when {
                days == 0L -> "Last backup: today"
                days == 1L -> "Last backup: yesterday"
                else -> "Last backup: $days days ago"
            }
        }

        fun isStale(lastBackupAt: Long, now: Long): Boolean =
            lastBackupAt <= 0L || now - lastBackupAt > STALE_AFTER_DAYS * 86_400_000L
    }
}
