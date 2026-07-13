package com.fadghost.notesapp

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadghost.notesapp.data.attach.AttachmentStorage
import com.fadghost.notesapp.data.backup.AttachmentRestoreFiles
import com.fadghost.notesapp.data.backup.BackupAttachment
import com.fadghost.notesapp.data.backup.BackupData
import com.fadghost.notesapp.data.backup.BackupDiaryEntry
import com.fadghost.notesapp.data.backup.BackupEvent
import com.fadghost.notesapp.data.backup.BackupNote
import com.fadghost.notesapp.data.backup.BackupReminder
import com.fadghost.notesapp.data.backup.ImportMode
import com.fadghost.notesapp.data.db.NotesDatabase
import com.fadghost.notesapp.data.db.entity.Attachment
import com.fadghost.notesapp.data.db.entity.DiaryEntry
import com.fadghost.notesapp.data.db.entity.Event
import com.fadghost.notesapp.data.db.entity.Note
import com.fadghost.notesapp.data.db.entity.NoteTagCrossRef
import com.fadghost.notesapp.data.db.entity.Reminder
import com.fadghost.notesapp.data.repo.NotesRepository
import java.io.File
import java.io.IOException
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDataIntegrityTest {
    private lateinit var context: Context
    private lateinit var db: NotesDatabase

    @Before fun setUp() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        AttachmentStorage.root(context.filesDir).deleteRecursively()
        db = Room.inMemoryDatabaseBuilder(context, NotesDatabase::class.java)
            .addCallback(NotesDatabase.CALLBACK)
            .allowMainThreadQueries()
            .build()
        db.openHelper.writableDatabase
    }

    @After fun tearDown() {
        db.close()
        AttachmentStorage.root(context.filesDir).deleteRecursively()
    }

    private fun repository(files: AttachmentRestoreFiles = AttachmentRestoreFiles()) = NotesRepository(
        db = db,
        noteDao = db.noteDao(),
        tagDao = db.tagDao(),
        folderDao = db.folderDao(),
        attachmentDao = db.attachmentDao(),
        diaryDao = db.diaryDao(),
        eventDao = db.eventDao(),
        reminderDao = db.reminderDao(),
        attachmentRestoreFiles = files,
        context = context
    )

    @Test fun normalizedTagCollisionKeepsIdentityAndAssignments() = runBlocking {
        val repo = repository()
        val noteId = repo.saveNote(Note(title = "Tagged", createdAt = 1, updatedAt = 1))
        val originalId = repo.createTag("Work", 10)
        db.tagDao().link(NoteTagCrossRef(noteId, originalId))

        val spacedId = repo.createTag("  Work  ", 20)
        val caseVariantId = repo.createTag("work", 30)

        assertEquals(originalId, spacedId)
        assertEquals(originalId, caseVariantId)
        assertEquals(listOf(originalId), db.tagDao().tagsForNote(noteId).map { it.id })
        assertEquals(1, db.tagDao().all().size)
        assertEquals(10, db.tagDao().getById(originalId)?.color)
    }

    @Test fun replaceClearsLiveAndTrashRowsAndRestoresDiaryCalendarAndSourceLink() = runBlocking {
        val repo = repository()
        val liveId = repo.saveNote(Note(title = "Live", createdAt = 1, updatedAt = 1))
        val trashId = repo.saveNote(Note(title = "Trash", createdAt = 2, updatedAt = 2))
        repo.softDelete(trashId, now = 3)
        val oldFile = File(AttachmentStorage.noteDir(context.filesDir, trashId), "old.txt")
        oldFile.parentFile!!.mkdirs()
        oldFile.writeText("old")
        db.attachmentDao().insert(
            Attachment(
                noteId = trashId,
                kind = Attachment.KIND_FILE,
                path = oldFile.absolutePath,
                displayName = "old.txt",
                mime = "text/plain",
                sizeBytes = oldFile.length(),
                createdAt = 3
            )
        )
        db.diaryDao().upsert(DiaryEntry(date = "2026-01-01", body = "old", createdAt = 1, updatedAt = 1))
        val oldEventId = db.eventDao().upsert(
            Event(title = "Old event", startAt = 10, endAt = 20, timezone = "Europe/London")
        )
        val oldReminderId = db.reminderDao().upsert(
            Reminder(title = "Old reminder", triggerAt = 10, timezone = "Europe/London")
        )

        val result = repo.importBackup(
            data = BackupData(
                notes = listOf(BackupNote(100, "Restored", "body", 100, 200, false, false)),
                diaryEntries = listOf(BackupDiaryEntry("2026-07-13", "new", 5, 100, 200)),
                events = listOf(
                    BackupEvent(200, "New event", 1_000, 2_000, "Europe/London", recurrence = "WEEKLY")
                ),
                reminders = listOf(
                    BackupReminder(300, "New reminder", 3_000, "Europe/London", sourceNoteId = 100)
                )
            ),
            mode = ImportMode.REPLACE
        )

        val notes = db.noteDao().allForReplace()
        assertEquals(1, notes.size)
        assertNotEquals(liveId, notes.single().id)
        assertNotEquals(trashId, notes.single().id)
        assertTrue(db.attachmentDao().all().isEmpty())
        assertFalse(AttachmentStorage.noteDir(context.filesDir, trashId).exists())
        assertEquals(listOf("2026-07-13"), db.diaryDao().allForBackup().map { it.date })
        assertEquals(listOf("New event"), db.eventDao().allForBackup().map { it.title })
        val restoredReminder = db.reminderDao().allForBackup().single()
        assertEquals("New reminder", restoredReminder.title)
        assertEquals(notes.single().id, restoredReminder.sourceNoteId)
        assertEquals(listOf(oldEventId), result.replacedEventIds)
        assertEquals(listOf(oldReminderId), result.replacedReminderIds)
        db.openHelper.writableDatabase.query("SELECT COUNT(*) FROM note_fts WHERE rowid IN (?, ?)", arrayOf(liveId, trashId)).use {
            assertTrue(it.moveToFirst())
            assertEquals(0, it.getInt(0))
        }
    }

    @Test fun attachmentWriteFailureRollsBackRowsAndCleansEveryCreatedFile() = runBlocking {
        val oldRepo = repository()
        val oldId = oldRepo.saveNote(Note(title = "Keep me", createdAt = 1, updatedAt = 1))
        val oldFile = File(AttachmentStorage.noteDir(context.filesDir, oldId), "keep.txt")
        oldFile.parentFile!!.mkdirs()
        oldFile.writeText("keep")
        db.attachmentDao().insert(
            Attachment(
                noteId = oldId,
                kind = Attachment.KIND_FILE,
                path = oldFile.absolutePath,
                displayName = "keep.txt",
                mime = "text/plain",
                sizeBytes = oldFile.length(),
                createdAt = 1
            )
        )

        val failingFiles = object : AttachmentRestoreFiles() {
            var writes = 0
            val cleaned = mutableListOf<File>()
            override fun write(file: File, bytes: ByteArray) {
                super.write(file, bytes)
                writes++
                if (writes == 2) throw IOException("simulated second-file failure")
            }

            override fun cleanup(file: File) {
                cleaned += file
                super.cleanup(file)
            }
        }
        val backup = BackupData(
            notes = listOf(BackupNote(10, "Imported", "[[att:1]] [[att:2]]", 10, 10, false, false)),
            attachments = listOf(
                BackupAttachment(1, 10, "file", "a.txt", "text/plain", 1, 10, zipPath = "attachments/10/a.txt"),
                BackupAttachment(2, 10, "file", "b.txt", "text/plain", 1, 10, zipPath = "attachments/10/b.txt")
            )
        )
        try {
            repository(failingFiles).importBackup(
                backup,
                ImportMode.REPLACE,
                mapOf("attachments/10/a.txt" to byteArrayOf(1), "attachments/10/b.txt" to byteArrayOf(2))
            )
            fail("restore should surface the file-write failure")
        } catch (expected: IOException) {
            assertEquals("simulated second-file failure", expected.message)
        }

        assertEquals(listOf(oldId), db.noteDao().allForReplace().map { it.id })
        assertEquals(1, db.attachmentDao().all().size)
        assertTrue(oldFile.isFile)
        assertEquals(2, failingFiles.cleaned.size)
        assertTrue(failingFiles.cleaned.none(File::exists))
    }

    @Test fun mergeKeepsExistingDiaryDateAndAppendsOtherDomains() = runBlocking {
        val repo = repository()
        db.diaryDao().upsert(
            DiaryEntry(date = "2026-07-13", body = "local wins", mood = 3, createdAt = 1, updatedAt = 2)
        )
        db.eventDao().upsert(Event(title = "Local event", startAt = 10, endAt = 20, timezone = "UTC"))
        db.reminderDao().upsert(Reminder(title = "Local reminder", triggerAt = 10, timezone = "UTC"))

        repo.importBackup(
            BackupData(
                diaryEntries = listOf(
                    BackupDiaryEntry("2026-07-13", "backup loses", 5, 3, 4),
                    BackupDiaryEntry("2026-07-14", "backup new", 4, 3, 4)
                ),
                events = listOf(BackupEvent(1, "Backup event", 30, 40, "UTC")),
                reminders = listOf(BackupReminder(1, "Backup reminder", 30, "UTC"))
            ),
            ImportMode.MERGE
        )

        val diary = db.diaryDao().allForBackup().associateBy { it.date }
        assertEquals("local wins", diary.getValue("2026-07-13").body)
        assertEquals("backup new", diary.getValue("2026-07-14").body)
        assertEquals(setOf("Local event", "Backup event"), db.eventDao().allForBackup().map { it.title }.toSet())
        assertEquals(
            setOf("Local reminder", "Backup reminder"),
            db.reminderDao().allForBackup().map { it.title }.toSet()
        )
    }
}
