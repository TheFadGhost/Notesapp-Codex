package com.fadghost.notesapp.data.backup

import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.security.MessageDigest
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Pure, Android-free backup ZIP serialization and validation. */
object BackupSerializer {

    private const val META_PATH = "metadata.json"
    private const val MANIFEST_PATH = "manifest.json"
    private const val NOTES_DIR = "notes/"
    private const val ATTACHMENTS_DIR = "attachments/"
    private const val MEMORY_DIR = "memory/"

    /**
     * Decompressed-size limits for a user-selected ZIP. Tests may pass smaller
     * limits to exercise the guards without allocating production-sized files.
     */
    data class Limits(
        val maxEntries: Int = 4_096,
        val maxEntryBytes: Long = 64L * 1024 * 1024,
        val maxTotalBytes: Long = 128L * 1024 * 1024
    ) {
        init {
            require(maxEntries > 0) { "maxEntries must be positive" }
            require(maxEntryBytes > 0) { "maxEntryBytes must be positive" }
            require(maxTotalBytes >= maxEntryBytes) {
                "maxTotalBytes must be at least maxEntryBytes"
            }
        }
    }

    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    /** Write a deterministic, fully-manifested backup ZIP. */
    fun export(
        data: BackupData,
        out: OutputStream,
        now: Long,
        attachmentBytes: Map<String, ByteArray> = emptyMap(),
        memoryBytes: Map<String, ByteArray> = emptyMap()
    ) {
        val limits = Limits()
        val entries = mutableListOf<ManifestEntry>()
        val writtenPaths = HashSet<String>()
        var writtenBytes = 0L

        ZipOutputStream(out).use { zip ->
            fun writePayload(path: String, bytes: ByteArray) {
                validatePayloadPath(path)
                require(writtenPaths.add(path)) { "Duplicate backup path: $path" }
                require(bytes.size.toLong() <= limits.maxEntryBytes) {
                    "Backup entry is too large: $path"
                }
                writtenBytes += bytes.size
                require(writtenBytes <= limits.maxTotalBytes) { "Backup is too large" }
                require(writtenPaths.size < limits.maxEntries) { "Backup has too many entries" }
                writeEntry(zip, path, bytes)
                entries += ManifestEntry(path, sha256(bytes), bytes.size.toLong())
            }

            for (note in data.notes) {
                val path = "$NOTES_DIR${note.id}.md"
                writePayload(path, renderNoteMarkdown(note).toByteArray(Charsets.UTF_8))
            }

            val expectedAttachmentPaths = data.attachments.map { it.zipPath }.toSet()
            require(expectedAttachmentPaths.size == data.attachments.size) {
                "Backup metadata contains duplicate attachment paths"
            }
            require(attachmentBytes.keys == expectedAttachmentPaths) {
                "Attachment metadata and payload files do not match"
            }
            for (attachment in data.attachments) {
                val bytes = attachmentBytes.getValue(attachment.zipPath)
                require(attachment.sizeBytes == bytes.size.toLong()) {
                    "Attachment size does not match its payload: ${attachment.zipPath}"
                }
                writePayload(attachment.zipPath, bytes)
            }

            for ((path, bytes) in memoryBytes) writePayload(path, bytes)

            val metadata = json.encodeToString(BackupData.serializer(), data)
                .toByteArray(Charsets.UTF_8)
            writePayload(META_PATH, metadata)

            val manifest = BackupManifest(
                formatVersion = BACKUP_FORMAT_VERSION,
                createdAt = now,
                noteCount = data.notes.size,
                folderCount = data.folders.size,
                tagCount = data.tags.size,
                entries = entries,
                attachmentCount = data.attachments.size,
                memoryFileCount = memoryBytes.size,
                diaryEntryCount = data.diaryEntries.size,
                eventCount = data.events.size,
                reminderCount = data.reminders.size
            )
            val manifestBytes = json.encodeToString(BackupManifest.serializer(), manifest)
                .toByteArray(Charsets.UTF_8)
            require(manifestBytes.size.toLong() <= limits.maxEntryBytes) { "Manifest is too large" }
            require(writtenBytes + manifestBytes.size <= limits.maxTotalBytes) { "Backup is too large" }
            require(writtenPaths.size + 1 <= limits.maxEntries) { "Backup has too many entries" }
            writeEntry(zip, MANIFEST_PATH, manifestBytes)
        }
    }

    /**
     * Read and structurally validate a backup. Content checksum/size mismatches are
     * retained in [BackupPreview] so the UI can explain corruption, while malformed
     * archives fail immediately.
     */
    fun parse(input: InputStream, limits: Limits = Limits()): BackupPreview {
        val files = LinkedHashMap<String, ByteArray>()
        var entryCount = 0
        var totalBytes = 0L

        ZipInputStream(input).use { zip ->
            var entry: ZipEntry? = zip.nextEntry
            while (entry != null) {
                entryCount++
                require(entryCount <= limits.maxEntries) { "Backup has too many entries" }
                require(!entry.isDirectory) { "Directory entries are not allowed: ${entry.name}" }
                validateArchivePath(entry.name)
                require(!files.containsKey(entry.name)) { "Duplicate backup path: ${entry.name}" }
                val bytes = readEntryBytes(zip, entry, totalBytes, limits)
                totalBytes += bytes.size
                files[entry.name] = bytes
                zip.closeEntry()
                entry = zip.nextEntry
            }
        }

        val manifestBytes = files[MANIFEST_PATH]
            ?: throw IllegalArgumentException("Not a Notesapp backup: manifest.json missing")
        val metadataBytes = files[META_PATH]
            ?: throw IllegalArgumentException("Not a Notesapp backup: metadata.json missing")

        val manifest = decodeManifest(manifestBytes)
        validateManifest(manifest, files, limits)
        val data = decodeData(metadataBytes)
        validateMetadata(data, manifest, files)

        val mismatches = manifest.entries.filter { item ->
            val actual = files[item.path]
            actual == null || actual.size.toLong() != item.bytes ||
                !sha256(actual).equals(item.sha256, ignoreCase = true)
        }.map { it.path }

        return BackupPreview(
            manifest = manifest,
            data = data,
            checksumMismatches = mismatches,
            attachmentFiles = files.filterKeys { it.startsWith(ATTACHMENTS_DIR) },
            memoryFiles = files.filterKeys { it.startsWith(MEMORY_DIR) }
        )
    }

    /** A note serialized as human-readable markdown with a small front-matter block. */
    fun renderNoteMarkdown(note: BackupNote): String = buildString {
        append("---\n")
        append("id: ${note.id}\n")
        append("created: ${note.createdAt}\n")
        append("updated: ${note.updatedAt}\n")
        append("pinned: ${note.pinned}\n")
        append("archived: ${note.archived}\n")
        note.folderName?.let { append("folder: $it\n") }
        if (note.tags.isNotEmpty()) append("tags: ${note.tags.joinToString(", ")}\n")
        append("---\n\n")
        if (note.title.isNotBlank()) append("# ${note.title}\n\n")
        append(note.body)
    }

    private fun decodeManifest(bytes: ByteArray): BackupManifest = try {
        json.decodeFromString(BackupManifest.serializer(), bytes.decodeToString())
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid backup manifest", error)
    }

    private fun decodeData(bytes: ByteArray): BackupData = try {
        json.decodeFromString(BackupData.serializer(), bytes.decodeToString())
    } catch (error: Exception) {
        throw IllegalArgumentException("Invalid backup metadata", error)
    }

    private fun validateManifest(
        manifest: BackupManifest,
        files: Map<String, ByteArray>,
        limits: Limits
    ) {
        require(manifest.formatVersion in MIN_SUPPORTED_BACKUP_FORMAT_VERSION..BACKUP_FORMAT_VERSION) {
            "Unsupported backup format ${manifest.formatVersion}"
        }
        require(manifest.noteCount >= 0 && manifest.folderCount >= 0 && manifest.tagCount >= 0) {
            "Backup manifest contains negative counts"
        }
        require(
            manifest.attachmentCount >= 0 && manifest.memoryFileCount >= 0 &&
                manifest.diaryEntryCount >= 0 && manifest.eventCount >= 0 &&
                manifest.reminderCount >= 0
        ) {
            "Backup manifest contains negative counts"
        }
        require(manifest.entries.size + 1 <= limits.maxEntries) {
            "Backup manifest has too many entries"
        }

        val manifestPaths = LinkedHashSet<String>()
        for (item in manifest.entries) {
            validatePayloadPath(item.path)
            require(manifestPaths.add(item.path)) { "Duplicate manifest path: ${item.path}" }
            require(item.sha256.matches(Regex("[0-9a-fA-F]{64}"))) {
                "Invalid checksum for ${item.path}"
            }
            require(item.bytes in 0..limits.maxEntryBytes) {
                "Invalid declared size for ${item.path}"
            }
        }
        require(META_PATH in manifestPaths) { "Backup manifest does not cover metadata.json" }

        val payloadPaths = files.keys - MANIFEST_PATH
        require(payloadPaths == manifestPaths) {
            val missing = manifestPaths - payloadPaths
            val unexpected = payloadPaths - manifestPaths
            "Backup payload does not match manifest (missing=$missing, unexpected=$unexpected)"
        }
    }

    private fun validateMetadata(
        data: BackupData,
        manifest: BackupManifest,
        files: Map<String, ByteArray>
    ) {
        require(manifest.noteCount == data.notes.size) { "Backup note count does not match metadata" }
        require(manifest.folderCount == data.folders.size) { "Backup folder count does not match metadata" }
        require(manifest.tagCount == data.tags.size) { "Backup tag count does not match metadata" }
        require(manifest.attachmentCount == data.attachments.size) {
            "Backup attachment count does not match metadata"
        }
        require(manifest.diaryEntryCount == data.diaryEntries.size) {
            "Backup diary-entry count does not match metadata"
        }
        require(manifest.eventCount == data.events.size) {
            "Backup event count does not match metadata"
        }
        require(manifest.reminderCount == data.reminders.size) {
            "Backup reminder count does not match metadata"
        }
        if (manifest.formatVersion == 1) {
            require(data.diaryEntries.isEmpty() && data.events.isEmpty() && data.reminders.isEmpty()) {
                "Backup format 1 cannot contain format-2 data"
            }
        }

        require(data.notes.all { it.id > 0L }) { "Backup contains an invalid note id" }
        val noteIds = data.notes.mapTo(LinkedHashSet()) { it.id }
        val expectedNotes = data.notes.map { "$NOTES_DIR${it.id}.md" }.toSet()
        require(expectedNotes.size == data.notes.size) { "Backup contains duplicate note ids" }
        val actualNotes = files.keys.filterTo(LinkedHashSet()) { it.startsWith(NOTES_DIR) }
        require(actualNotes == expectedNotes) { "Backup note files do not match metadata" }

        val expectedAttachments = data.attachments.map { it.zipPath }.toSet()
        require(expectedAttachments.size == data.attachments.size) {
            "Backup metadata contains duplicate attachment paths"
        }
        expectedAttachments.forEach(::validateAttachmentPath)
        val attachmentIds = data.attachments.mapTo(LinkedHashSet()) { it.id }
        require(attachmentIds.size == data.attachments.size && attachmentIds.none { it <= 0L }) {
            "Backup contains duplicate or invalid attachment ids"
        }
        data.attachments.forEach { attachment ->
            require(attachment.noteId in noteIds) { "Attachment references a missing note" }
            require(attachment.sizeBytes >= 0L) { "Attachment has a negative size" }
            require(attachment.zipPath.split('/')[1].toLong() == attachment.noteId) {
                "Attachment path does not match its note id"
            }
            require(attachment.annotatedOfId == null || attachment.annotatedOfId in attachmentIds) {
                "Attachment annotation references a missing attachment"
            }
            val payloadSize = files.getValue(attachment.zipPath).size.toLong()
            require(
                payloadSize == attachment.sizeBytes ||
                    (manifest.formatVersion == 1 && attachment.sizeBytes == 0L)
            ) {
                "Attachment size does not match its payload"
            }
        }
        val actualAttachments = files.keys.filterTo(LinkedHashSet()) {
            it.startsWith(ATTACHMENTS_DIR)
        }
        require(actualAttachments == expectedAttachments) {
            "Backup attachment files do not match metadata"
        }

        val memoryPaths = files.keys.filter { it.startsWith(MEMORY_DIR) }
        require(manifest.memoryFileCount == memoryPaths.size) {
            "Backup memory-file count does not match payload"
        }
        if (memoryPaths.any { it.startsWith("${MEMORY_DIR}entries/") }) {
            require("${MEMORY_DIR}index.md" in memoryPaths) {
                "Backup memory entries are missing their derived index"
            }
        }

        if (manifest.formatVersion >= 2) {
            requireUniqueNormalized(data.folders.map { it.name }, "folder")
            requireUniqueNormalized(data.tags.map { it.name }, "tag")
        }

        val diaryDates = LinkedHashSet<String>()
        data.diaryEntries.forEach { entry ->
            require(runCatching { LocalDate.parse(entry.date) }.isSuccess) {
                "Diary entry has an invalid date"
            }
            require(diaryDates.add(entry.date)) { "Backup contains duplicate diary dates" }
            require(entry.mood == null || entry.mood in 1..5) { "Diary entry has an invalid mood" }
        }

        val recurrenceNames = setOf("NONE", "DAILY", "WEEKLY", "MONTHLY")
        val eventIds = LinkedHashSet<Long>()
        data.events.forEach { event ->
            require(event.id > 0L && eventIds.add(event.id)) {
                "Backup contains duplicate or invalid event ids"
            }
            val endAt = event.endAt
            require(endAt == null || endAt >= event.startAt) { "Event ends before it starts" }
            require(runCatching { ZoneId.of(event.timezone) }.isSuccess) { "Event has an invalid timezone" }
            require(event.recurrence in recurrenceNames) { "Event has an invalid recurrence" }
            require(event.notificationLeadMinutes == null || event.notificationLeadMinutes in 0..10_080) {
                "Event has an invalid notification lead"
            }
        }

        val reminderIds = LinkedHashSet<Long>()
        data.reminders.forEach { reminder ->
            require(reminder.id > 0L && reminderIds.add(reminder.id)) {
                "Backup contains duplicate or invalid reminder ids"
            }
            require(runCatching { ZoneId.of(reminder.timezone) }.isSuccess) {
                "Reminder has an invalid timezone"
            }
            require(reminder.recurrence in recurrenceNames) { "Reminder has an invalid recurrence" }
            require(reminder.sourceNoteId == null || reminder.sourceNoteId in noteIds) {
                "Reminder references a note that is not in the backup"
            }
        }
    }

    private fun requireUniqueNormalized(names: List<String>, kind: String) {
        val normalized = LinkedHashSet<String>()
        names.forEach { raw ->
            val value = raw.trim().replace(Regex("\\s+"), " ")
            require(value.isNotBlank()) { "Backup contains a blank $kind name" }
            require(normalized.add(value.lowercase(Locale.ROOT))) { "Backup contains duplicate $kind names" }
        }
    }

    private fun readEntryBytes(
        zip: ZipInputStream,
        entry: ZipEntry,
        totalBefore: Long,
        limits: Limits
    ): ByteArray {
        if (entry.size >= 0) {
            require(entry.size <= limits.maxEntryBytes) { "Backup entry is too large: ${entry.name}" }
            require(totalBefore + entry.size <= limits.maxTotalBytes) { "Backup is too large" }
        }
        val initialSize = entry.size.takeIf { it in 1..65_536 }?.toInt() ?: 8_192
        val out = ByteArrayOutputStream(initialSize)
        val buffer = ByteArray(8_192)
        var entryBytes = 0L
        while (true) {
            val read = zip.read(buffer)
            if (read < 0) break
            entryBytes += read
            require(entryBytes <= limits.maxEntryBytes) { "Backup entry is too large: ${entry.name}" }
            require(totalBefore + entryBytes <= limits.maxTotalBytes) { "Backup is too large" }
            out.write(buffer, 0, read)
        }
        return out.toByteArray()
    }

    private fun validateArchivePath(path: String) {
        require(path.isNotBlank() && path.length <= 240) { "Invalid backup path" }
        require('\\' !in path && '\u0000' !in path && !path.startsWith('/')) {
            "Unsafe backup path: $path"
        }
        val segments = path.split('/')
        require(segments.none { it.isBlank() || it == "." || it == ".." }) {
            "Unsafe backup path: $path"
        }
        require(path == MANIFEST_PATH || isPayloadPath(path)) { "Unexpected backup path: $path" }
    }

    private fun validatePayloadPath(path: String) {
        validateArchivePath(path)
        require(path != MANIFEST_PATH) { "manifest.json cannot be a payload entry" }
    }

    private fun isPayloadPath(path: String): Boolean = when {
        path == META_PATH -> true
        path.matches(Regex("notes/[0-9]+\\.md")) -> true
        path.startsWith(ATTACHMENTS_DIR) -> runCatching { validateAttachmentPath(path) }.isSuccess
        path == "${MEMORY_DIR}index.md" -> true
        path.matches(Regex("memory/entries/[a-z0-9-]{1,40}\\.md")) -> true
        else -> false
    }

    private fun validateAttachmentPath(path: String) {
        val parts = path.split('/')
        require(
            parts.size == 3 && parts[0] == "attachments" &&
                parts[1].isNotBlank() && parts[1].all(Char::isDigit) &&
                parts[2].isNotBlank()
        ) { "Invalid attachment path: $path" }
    }

    private fun writeEntry(zip: ZipOutputStream, path: String, bytes: ByteArray) {
        val entry = ZipEntry(path)
        entry.time = 0L
        zip.putNextEntry(entry)
        zip.write(bytes)
        zip.closeEntry()
    }

    fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        val result = StringBuilder(digest.size * 2)
        for (byte in digest) {
            val value = byte.toInt() and 0xFF
            result.append(HEX[value ushr 4]).append(HEX[value and 0x0F])
        }
        return result.toString()
    }

    private val HEX = "0123456789abcdef".toCharArray()
}
