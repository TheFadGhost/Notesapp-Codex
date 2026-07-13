package com.fadghost.notesapp

import com.fadghost.notesapp.data.backup.BackupAttachment
import com.fadghost.notesapp.data.backup.BackupData
import com.fadghost.notesapp.data.backup.BackupDiaryEntry
import com.fadghost.notesapp.data.backup.BackupEvent
import com.fadghost.notesapp.data.backup.BackupFolder
import com.fadghost.notesapp.data.backup.BackupNote
import com.fadghost.notesapp.data.backup.BackupReminder
import com.fadghost.notesapp.data.backup.BackupRestoreGuard
import com.fadghost.notesapp.data.backup.BackupSerializer
import com.fadghost.notesapp.data.backup.BackupTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class BackupRoundTripTest {

    private fun sample() = BackupData(
        notes = listOf(
            BackupNote(1, "Groceries", "- milk\n- eggs", 100, 200, pinned = true, archived = false, tags = listOf("home")),
            BackupNote(2, "Ideas", "**big** idea", 101, 201, pinned = false, archived = false, folderName = "Work")
        ),
        folders = listOf(BackupFolder("Work")),
        tags = listOf(BackupTag("home", -0x10000)),
        diaryEntries = listOf(BackupDiaryEntry("2026-07-13", "A good day", 4, 300, 400)),
        events = listOf(
            BackupEvent(
                id = 8,
                title = "Dentist",
                startAt = 1_800_000_000_000L,
                endAt = 1_800_003_600_000L,
                timezone = "Europe/London",
                recurrence = "MONTHLY",
                notificationLeadMinutes = 30,
                lastNotifiedOccurrenceAt = 1_700_000_000_000L
            )
        ),
        reminders = listOf(
            BackupReminder(
                id = 9,
                title = "Call dentist",
                triggerAt = 1_800_000_000_000L,
                timezone = "Europe/London",
                recurrence = "NONE",
                sourceNoteId = 1,
                lastNotifiedTriggerAt = 1_800_000_000_000L
            )
        )
    )

    private fun exported(
        data: BackupData = sample(),
        memory: Map<String, ByteArray> = emptyMap(),
        attachments: Map<String, ByteArray> = emptyMap()
    ): ByteArray = ByteArrayOutputStream().also {
        BackupSerializer.export(
            data,
            it,
            now = 1234L,
            attachmentBytes = attachments,
            memoryBytes = memory
        )
    }.toByteArray()

    private fun repack(
        source: ByteArray,
        transform: (String, ByteArray) -> ByteArray
    ): ByteArray = ByteArrayOutputStream().also { destination ->
        ZipInputStream(ByteArrayInputStream(source)).use { input ->
            ZipOutputStream(destination).use { output ->
                var entry = input.nextEntry
                while (entry != null) {
                    val bytes = transform(entry.name, input.readBytes())
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(bytes)
                    output.closeEntry()
                    entry = input.nextEntry
                }
            }
        }
    }.toByteArray()

    private fun zipOf(vararg entries: Pair<String, ByteArray>): ByteArray =
        ByteArrayOutputStream().also { destination ->
            ZipOutputStream(destination).use { output ->
                entries.forEach { (path, bytes) ->
                    output.putNextEntry(ZipEntry(path))
                    output.write(bytes)
                    output.closeEntry()
                }
            }
        }.toByteArray()

    private fun replaceAscii(source: ByteArray, from: String, to: String): ByteArray {
        require(from.length == to.length)
        val result = source.copyOf()
        val needle = from.toByteArray()
        val replacement = to.toByteArray()
        for (start in 0..result.size - needle.size) {
            if (needle.indices.all { result[start + it] == needle[it] }) {
                replacement.copyInto(result, destinationOffset = start)
            }
        }
        return result
    }

    @Test fun exportThenParseRoundTrips() {
        val data = sample()
        val preview = BackupSerializer.parse(ByteArrayInputStream(exported(data)))

        assertTrue("checksums must match", preview.isIntact)
        assertEquals(2, preview.manifest.formatVersion)
        assertEquals(1234L, preview.manifest.createdAt)
        assertEquals(2, preview.manifest.noteCount)
        assertEquals(1, preview.manifest.folderCount)
        assertEquals(1, preview.manifest.tagCount)
        assertEquals(1, preview.manifest.diaryEntryCount)
        assertEquals(1, preview.manifest.eventCount)
        assertEquals(1, preview.manifest.reminderCount)
        assertEquals(data.notes, preview.data.notes)
        assertEquals(data.folders, preview.data.folders)
        assertEquals(data.tags, preview.data.tags)
        assertEquals(data.diaryEntries, preview.data.diaryEntries)
        assertEquals(data.events, preview.data.events)
        assertEquals(data.reminders, preview.data.reminders)
    }

    @Test fun everyManifestEntryHasChecksum() {
        val preview = BackupSerializer.parse(ByteArrayInputStream(exported()))
        assertEquals(3, preview.manifest.entries.size)
        preview.manifest.entries.forEach { assertEquals(64, it.sha256.length) }
    }

    @Test fun tamperedContentIsDetectedAndRestoreGuardBlocksIt() {
        val changed = repack(exported()) { path, original ->
            if (path == "notes/1.md") "TAMPERED".toByteArray() else original
        }
        val preview = BackupSerializer.parse(ByteArrayInputStream(changed))
        assertTrue(preview.checksumMismatches.contains("notes/1.md"))
        assertFalse(preview.isIntact)
        assertThrows(IllegalArgumentException::class.java) {
            BackupRestoreGuard.requireIntact(preview)
        }
    }

    @Test fun memoryPayloadRoundTripsAndIsManifested() {
        val memory = mapOf(
            "memory/index.md" to "- saved | fact | Saved".toByteArray(),
            "memory/entries/saved.md" to
                "---\nslug: saved\ntitle: Saved\ntype: fact\n---\nBody".toByteArray()
        )
        val preview = BackupSerializer.parse(ByteArrayInputStream(exported(memory = memory)))
        assertEquals(memory.keys, preview.memoryFiles.keys)
        assertEquals(2, preview.manifest.memoryFileCount)
    }

    @Test fun unsupportedFormatIsRejected() {
        val changed = repack(exported()) { path, original ->
            if (path == "manifest.json") {
                original.decodeToString().replace("\"formatVersion\": 2", "\"formatVersion\": 99")
                    .toByteArray()
            } else original
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupSerializer.parse(ByteArrayInputStream(changed))
        }
    }

    @Test fun genuineVersionOneShapeStillParsesWithNewDomainsEmpty() {
        val legacyData = sample().copy(
            diaryEntries = emptyList(),
            events = emptyList(),
            reminders = emptyList()
        )
        val v1 = repack(exported(legacyData)) { path, original ->
            val text = original.decodeToString()
            when (path) {
                "manifest.json" -> text
                    .replace("\"formatVersion\": 2", "\"formatVersion\": 1")
                    .replace(
                        Regex(",\\s*\"diaryEntryCount\": 0,\\s*\"eventCount\": 0,\\s*\"reminderCount\": 0"),
                        ""
                    )
                    .toByteArray()
                "metadata.json" -> text
                    .replace(
                        Regex(",\\s*\"diaryEntries\": \\[],\\s*\"events\": \\[],\\s*\"reminders\": \\[]"),
                        ""
                    )
                    .toByteArray()
                else -> original
            }
        }

        val preview = BackupSerializer.parse(ByteArrayInputStream(v1))
        assertEquals(1, preview.manifest.formatVersion)
        assertTrue(preview.data.diaryEntries.isEmpty())
        assertTrue(preview.data.events.isEmpty())
        assertTrue(preview.data.reminders.isEmpty())
    }

    @Test fun v2RejectsInvalidDomainReferencesAndAttachmentSizes() {
        val attachment = BackupAttachment(
            id = 3,
            noteId = 1,
            kind = "image",
            displayName = "photo.jpg",
            mime = "image/jpeg",
            sizeBytes = 3,
            createdAt = 10,
            zipPath = "attachments/1/photo.jpg"
        )
        val valid = sample().copy(attachments = listOf(attachment))
        val bytes = mapOf(attachment.zipPath to byteArrayOf(1, 2, 3))
        assertTrue(BackupSerializer.parse(ByteArrayInputStream(exported(valid, attachments = bytes))).isIntact)

        assertThrows(IllegalArgumentException::class.java) {
            exported(valid.copy(attachments = listOf(attachment.copy(sizeBytes = 4))), attachments = bytes)
        }
        assertThrows(IllegalArgumentException::class.java) {
            val missingSource = sample().copy(
                reminders = listOf(sample().reminders.single().copy(sourceNoteId = 999))
            )
            BackupSerializer.parse(ByteArrayInputStream(exported(missingSource)))
        }
    }

    @Test fun v2RejectsNormalizedTagCollisions() {
        val data = sample().copy(tags = listOf(BackupTag("Work", 1), BackupTag("  work  ", 2)))
        assertThrows(IllegalArgumentException::class.java) {
            BackupSerializer.parse(ByteArrayInputStream(exported(data)))
        }
    }

    @Test fun manifestCountsMustMatchMetadata() {
        val changed = repack(exported()) { path, original ->
            if (path == "manifest.json") {
                original.decodeToString().replace("\"noteCount\": 2", "\"noteCount\": 3")
                    .toByteArray()
            } else original
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupSerializer.parse(ByteArrayInputStream(changed))
        }
    }

    @Test fun duplicateUnsafeAndUnexpectedPathsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            val duplicate = replaceAscii(
                zipOf("metadata.json" to byteArrayOf(1), "metadata.jsox" to byteArrayOf(2)),
                from = "metadata.jsox",
                to = "metadata.json"
            )
            BackupSerializer.parse(
                ByteArrayInputStream(duplicate)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupSerializer.parse(ByteArrayInputStream(zipOf("../metadata.json" to byteArrayOf(1))))
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupSerializer.parse(ByteArrayInputStream(zipOf("surprise.txt" to byteArrayOf(1))))
        }
    }

    @Test fun entryCountPerFileAndTotalLimitsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) {
            BackupSerializer.parse(
                ByteArrayInputStream(zipOf("metadata.json" to byteArrayOf(1), "manifest.json" to byteArrayOf(2))),
                BackupSerializer.Limits(maxEntries = 1, maxEntryBytes = 10, maxTotalBytes = 20)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupSerializer.parse(
                ByteArrayInputStream(zipOf("metadata.json" to byteArrayOf(1, 2, 3, 4))),
                BackupSerializer.Limits(maxEntries = 2, maxEntryBytes = 3, maxTotalBytes = 6)
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            BackupSerializer.parse(
                ByteArrayInputStream(
                    zipOf("metadata.json" to byteArrayOf(1, 2, 3), "manifest.json" to byteArrayOf(4, 5, 6))
                ),
                BackupSerializer.Limits(maxEntries = 2, maxEntryBytes = 3, maxTotalBytes = 5)
            )
        }
    }

    @Test fun sha256IsStable() {
        assertEquals(
            "2cf24dba5fb0a30e26e83b2ac5b9e29e1b161e5c1fa7425e73043362938b9824",
            BackupSerializer.sha256("hello".toByteArray())
        )
    }
}
