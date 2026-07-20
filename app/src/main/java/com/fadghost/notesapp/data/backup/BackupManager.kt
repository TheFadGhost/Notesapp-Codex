package com.fadghost.notesapp.data.backup

import android.content.Context
import android.net.Uri
import com.fadghost.notesapp.alarm.EventAlarm
import com.fadghost.notesapp.alarm.ReminderAlarm
import com.fadghost.notesapp.data.ai.AiPreferences
import com.fadghost.notesapp.data.memory.MemoryRepository
import com.fadghost.notesapp.data.prefs.ThemeMode
import com.fadghost.notesapp.data.prefs.ThemePreferences
import com.fadghost.notesapp.data.repo.NotesRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Android SAF wrapper around [BackupSerializer] (PLAN.md §6/§12). Reads/writes the
 * user-picked ZIP through the ContentResolver; all format logic stays in the pure
 * serializer. The API key / settings secrets are never part of [BackupData], so
 * they can't leak into an export.
 */
@Singleton
class BackupManager @Inject constructor(
    @ApplicationContext private val context: Context,
    private val repository: NotesRepository,
    private val memoryRepository: MemoryRepository,
    private val reminderAlarm: ReminderAlarm,
    private val eventAlarm: EventAlarm,
    private val themePrefs: ThemePreferences,
    private val aiPrefs: AiPreferences
) {
    /** Export every note (attachment files + the memory vault) to the user-picked ZIP [target]. */
    suspend fun export(target: Uri): Int = withContext(Dispatchers.IO) {
        val data = repository.buildBackup().copy(settings = collectSettings())
        val attachmentBytes = repository.exportAttachmentBytes()
        val memoryBytes = memoryRepository.exportBytes()
        context.contentResolver.openOutputStream(target)?.use { out ->
            BackupSerializer.export(
                data, out, now = System.currentTimeMillis(),
                attachmentBytes = attachmentBytes, memoryBytes = memoryBytes
            )
        } ?: error("Could not open $target for writing")
        data.notes.size
    }

    /** Read a backup for the import preview (counts + checksum verification). */
    suspend fun preview(source: Uri): BackupPreview = withContext(Dispatchers.IO) {
        context.contentResolver.openInputStream(source)?.use { BackupSerializer.parse(it) }
            ?: error("Could not open $source for reading")
    }

    /** Commit a previewed backup with the chosen [mode]. */
    suspend fun restore(preview: BackupPreview, mode: ImportMode) = withContext(Dispatchers.IO) {
        BackupRestoreGuard.requireIntact(preview)
        val result = repository.importBackup(preview.data, mode, preview.attachmentFiles)
        if (mode == ImportMode.REPLACE) {
            result.replacedReminderIds.forEach(reminderAlarm::cancelReminder)
            result.replacedEventIds.forEach(eventAlarm::cancelEvent)
        }
        // Imported pending rows need alarms immediately; do not wait for a reboot/cold start.
        reminderAlarm.rescheduleAll()
        eventAlarm.rescheduleAll()
        memoryRepository.importFiles(
            files = preview.memoryFiles,
            replace = mode == ImportMode.REPLACE
        )
        // Preferences travel with the backup (IDEAS #97) but only REPLACE applies them —
        // a merge keeps this device's current look and model choices.
        if (mode == ImportMode.REPLACE) preview.data.settings?.let { applySettings(it) }
    }

    /** Snapshot the non-secret preferences worth surviving a reinstall. */
    private suspend fun collectSettings() = BackupSettings(
        themeMode = themePrefs.themeMode.first().name,
        accentIndex = themePrefs.accentIndex.first(),
        reduceMotion = themePrefs.reduceMotion.first(),
        textScale = themePrefs.textScale.first(),
        textModel = aiPrefs.textModel.first(),
        sttModel = aiPrefs.sttModel.first(),
        autoCleanTranscript = aiPrefs.autoCleanTranscript.first(),
        monthlyBudgetUsd = aiPrefs.monthlyBudgetUsd.first(),
        favoriteModels = aiPrefs.favorites.first().toList()
    )

    private suspend fun applySettings(s: BackupSettings) {
        s.themeMode?.let { name ->
            runCatching { ThemeMode.valueOf(name) }.getOrNull()?.let { themePrefs.setThemeMode(it) }
        }
        s.accentIndex?.let { themePrefs.setAccentIndex(it) }
        s.reduceMotion?.let { themePrefs.setReduceMotion(it) }
        s.textScale?.let { themePrefs.setTextScale(it) }
        s.textModel?.takeIf { it.isNotBlank() }?.let { aiPrefs.setTextModel(it) }
        s.sttModel?.takeIf { it.isNotBlank() }?.let { aiPrefs.setSttModel(it) }
        s.autoCleanTranscript?.let { aiPrefs.setAutoCleanTranscript(it) }
        s.monthlyBudgetUsd?.let { aiPrefs.setMonthlyBudgetUsd(it) }
        // Favourites: additive union — restoring can only add stars, never lose them.
        s.favoriteModels.forEach { id ->
            if (id.isNotBlank()) aiPrefs.favoriteIfAbsent(id)
        }
    }
}
