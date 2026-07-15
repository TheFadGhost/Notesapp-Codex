package com.fadghost.notesapp.data.backup

import kotlinx.serialization.Serializable

/**
 * Backup wire format (PLAN.md §6/§12): a ZIP of one `.md` file per note plus a
 * JSON metadata blob and a manifest of SHA-256 checksums. The API key and any
 * secret is NEVER represented here — these DTOs only carry note content.
 */

const val BACKUP_FORMAT_VERSION = 2
const val MIN_SUPPORTED_BACKUP_FORMAT_VERSION = 1

@Serializable
data class BackupNote(
    val id: Long,
    val title: String,
    val body: String,
    val createdAt: Long,
    val updatedAt: Long,
    val pinned: Boolean,
    val archived: Boolean,
    val folderName: String? = null,
    val tags: List<String> = emptyList()
)

@Serializable
data class BackupFolder(val name: String)

@Serializable
data class BackupTag(val name: String, val color: Int)

@Serializable
data class BackupDiaryEntry(
    val date: String,
    val body: String,
    val mood: Int? = null,
    val createdAt: Long,
    val updatedAt: Long
)

@Serializable
data class BackupEvent(
    val id: Long,
    val title: String,
    val startAt: Long,
    val endAt: Long? = null,
    val timezone: String,
    val notes: String? = null,
    val recurrence: String = "NONE",
    val notificationLeadMinutes: Int? = null,
    val lastNotifiedOccurrenceAt: Long? = null
)

@Serializable
data class BackupReminder(
    val id: Long,
    val title: String,
    val triggerAt: Long,
    val timezone: String,
    val done: Boolean = false,
    val snoozedUntil: Long? = null,
    val alarmFired: Boolean = false,
    val recurrence: String = "NONE",
    /** Old backup note id; remapped to the restored note id on import. */
    val sourceNoteId: Long? = null,
    val lastNotifiedTriggerAt: Long? = null
)

/**
 * An attachment's metadata (M-A). The file bytes live in the ZIP at [zipPath]; on
 * restore the [id]/[noteId]/[annotatedOfId] are remapped to freshly-assigned ids and
 * the note bodies' `[[att:<id>]]` tokens are rewritten to match.
 */
@Serializable
data class BackupAttachment(
    val id: Long,
    val noteId: Long,
    val kind: String,
    val displayName: String,
    val mime: String,
    val sizeBytes: Long,
    val createdAt: Long,
    val annotatedOfId: Long? = null,
    val ocrText: String? = null,
    val description: String? = null,
    /** ZIP-relative path of the stored bytes, e.g. `attachments/3/uuid.jpg`. */
    val zipPath: String
)

/** Everything exported, in memory. Contains no secrets. */
@Serializable
data class BackupData(
    val notes: List<BackupNote> = emptyList(),
    val folders: List<BackupFolder> = emptyList(),
    val tags: List<BackupTag> = emptyList(),
    val attachments: List<BackupAttachment> = emptyList(),
    /** Added in format v2; absent v1 fields decode to empty lists. */
    val diaryEntries: List<BackupDiaryEntry> = emptyList(),
    val events: List<BackupEvent> = emptyList(),
    val reminders: List<BackupReminder> = emptyList()
)

@Serializable
data class ManifestEntry(val path: String, val sha256: String, val bytes: Long)

@Serializable
data class BackupManifest(
    val formatVersion: Int,
    val createdAt: Long,
    val noteCount: Int,
    val folderCount: Int,
    val tagCount: Int,
    val entries: List<ManifestEntry>,
    val attachmentCount: Int = 0,
    /** Number of memory-vault files (index.md + per-entry markdown) in the ZIP (M-B). */
    val memoryFileCount: Int = 0,
    /** Format-v2 metadata counts; default zero keeps genuine v1 manifests readable. */
    val diaryEntryCount: Int = 0,
    val eventCount: Int = 0,
    val reminderCount: Int = 0
)

/** Result of reading a backup ZIP without committing it — drives the import preview. */
data class BackupPreview(
    val manifest: BackupManifest,
    val data: BackupData,
    /** Paths whose recomputed checksum did not match the manifest. Empty == intact. */
    val checksumMismatches: List<String>,
    /** ZIP-relative path -> file bytes for attachments, applied on restore. */
    val attachmentFiles: Map<String, ByteArray> = emptyMap(),
    /** ZIP-relative path (`memory/...`) -> file bytes for the memory vault (M-B). */
    val memoryFiles: Map<String, ByteArray> = emptyMap()
) {
    val isIntact: Boolean get() = checksumMismatches.isEmpty()
}

/** Pure restore guard shared by the UI and manager. */
object BackupRestoreGuard {
    fun requireIntact(preview: BackupPreview) {
        require(preview.isIntact) {
            "Backup failed checksum verification for ${preview.checksumMismatches.size} file(s)"
        }
    }
}

/** Import strategy chosen after preview (PLAN.md §12 — never blind overwrite). */
enum class ImportMode { REPLACE, MERGE }

/** Rows whose previously-armed alarms/notifications must be cancelled after REPLACE. */
data class BackupImportResult(
    val replacedReminderIds: List<Long> = emptyList(),
    val replacedEventIds: List<Long> = emptyList()
)
