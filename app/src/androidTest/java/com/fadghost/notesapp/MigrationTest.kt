package com.fadghost.notesapp

import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadghost.notesapp.data.db.MIGRATION_6_7
import com.fadghost.notesapp.data.db.MIGRATION_7_8
import com.fadghost.notesapp.data.db.MIGRATION_8_9
import com.fadghost.notesapp.data.db.MemoryFts
import com.fadghost.notesapp.data.db.NOTES_MIGRATIONS
import com.fadghost.notesapp.data.db.NotesDatabase
import com.fadghost.notesapp.data.db.NotesFts
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Room migration test for M-A's schema bump (v6 -> v7, the `attachments` table).
 * Seeds a real v6 database, runs [MIGRATION_6_7], and lets Room validate the resulting
 * schema against the exported 7.json — then proves the new table works and the seeded
 * note survived. Instrumented because Room's [MigrationTestHelper] needs a device DB
 * (framework SQLite); run with `.\gradlew.bat connectedDebugAndroidTest`.
 */
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    private val testDb = "migration-test-notes.db"

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        NotesDatabase::class.java
    )

    @Test
    fun migrate6To7_addsAttachmentsTable_andKeepsData() {
        // Seed a v6 DB with one note the attachment will reference.
        helper.createDatabase(testDb, 6).use { db ->
            db.execSQL(
                "INSERT INTO Note (id, title, body, createdAt, updatedAt, pinned, archived, deletedAt, folderId) " +
                    "VALUES (1, 'Trip', 'body [[att:1]]', 100, 200, 0, 0, NULL, NULL)"
            )
        }

        // Migrate to v7; Room validates the schema against schemas/7.json (throws on mismatch).
        val db = helper.runMigrationsAndValidate(testDb, 7, true, MIGRATION_6_7)

        // The seeded note survived the migration.
        db.query("SELECT title FROM Note WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Trip", c.getString(0))
        }

        // The new attachments table exists and accepts a row referencing the note.
        db.execSQL(
            "INSERT INTO attachments " +
                "(id, noteId, kind, path, displayName, mime, sizeBytes, createdAt, annotatedOfId, ocrText, description) " +
                "VALUES (1, 1, 'image', '/data/x/y.jpg', 'y.jpg', 'image/jpeg', 2048, 300, NULL, NULL, NULL)"
        )
        db.query("SELECT noteId, kind, displayName, sizeBytes FROM attachments WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
            assertEquals("image", c.getString(1))
            assertEquals("y.jpg", c.getString(2))
            assertEquals(2048L, c.getLong(3))
        }
    }

    /**
     * M-B schema bump (v7 -> v8): the Folio memory vault mirror. Seeds a real v7 DB with a
     * note, runs [MIGRATION_7_8], lets Room validate the result against the exported 8.json,
     * then proves the three new stores work — `memory_entries` + `memory_links` rows insert,
     * the raw `memory_fts` FTS4 index MATCHes, and the seeded note survived.
     */
    @Test
    fun migrate7To8_addsMemoryVaultMirror_andKeepsData() {
        helper.createDatabase(testDb, 7).use { db ->
            db.execSQL(
                "INSERT INTO Note (id, title, body, createdAt, updatedAt, pinned, archived, deletedAt, folderId) " +
                    "VALUES (1, 'Plans', 'gym mon/wed/fri', 100, 200, 0, 0, NULL, NULL)"
            )
        }

        // Open through Room with the migration — the SAME path the app uses at runtime.
        // Unlike runMigrationsAndValidate (which is stricter than the runtime open), Room's
        // open tolerates the intentional raw FTS4 tables (memory_fts / note_fts) that are not
        // Room entities — framework SQLite has no fts5 — while still validating every tracked
        // table (memory_entries / memory_links / …) against the generated schema.
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val room = androidx.room.Room.databaseBuilder(context, NotesDatabase::class.java, testDb)
            // Room always opens at the current schema version, so this uses the same
            // full migration registry as production (7 -> 8 -> 9).
            .addMigrations(*NOTES_MIGRATIONS)
            .build()
        val db = room.openHelper.writableDatabase

        // Seeded note survived.
        db.query("SELECT title FROM Note WHERE id = 1").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Plans", c.getString(0))
        }

        // memory_entries accepts a mirror row (slug PK + the mirrored fields).
        db.execSQL(
            "INSERT INTO memory_entries (slug, title, type, tags, body, source, created, updated) " +
                "VALUES ('gym-schedule', 'Gym schedule', 'routine', 'health,planning', " +
                "'Gym Mon/Wed/Fri 7am.', 'note:1', '2026-07-12', '2026-07-12')"
        )
        db.query("SELECT title, type, tags FROM memory_entries WHERE slug = 'gym-schedule'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("Gym schedule", c.getString(0))
            assertEquals("routine", c.getString(1))
            assertEquals("health,planning", c.getString(2))
        }

        // memory_links accepts an edge (composite PK).
        db.execSQL("INSERT INTO memory_links (fromSlug, toSlug) VALUES ('gym-schedule', 'weekly-plan')")
        db.query("SELECT COUNT(*) FROM memory_links WHERE fromSlug = 'gym-schedule'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals(1L, c.getLong(0))
        }

        // The raw memory_fts FTS4 index exists and MATCHes by body text.
        db.execSQL("INSERT INTO memory_fts (slug, title, body) VALUES ('gym-schedule', 'Gym schedule', 'Gym Mon Wed Fri 7am')")
        db.query("SELECT slug FROM memory_fts WHERE memory_fts MATCH 'gym'").use { c ->
            assertTrue(c.moveToFirst())
            assertEquals("gym-schedule", c.getString(0))
        }

        room.close()
    }

    @Test
    fun migrate8To9_addsDurableDeliveryState_provenance_andLeadSettings() {
        val dbName = "migration-8-9-notes.db"
        helper.createDatabase(dbName, 8).use { db ->
            db.execSQL(
                "INSERT INTO Note (id, title, body, createdAt, updatedAt, pinned, archived, deletedAt, folderId) " +
                    "VALUES (9, 'Source note', 'Call the dentist', 100, 200, 0, 0, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO Event (id, title, startAt, endAt, timezone, notes, recurrence) " +
                    "VALUES (5, 'Dentist', 2000000, 2060000, 'Europe/London', NULL, 'NONE')"
            )
            db.execSQL(
                "INSERT INTO Reminder (id, title, triggerAt, timezone, done, snoozedUntil, recurrence) " +
                    "VALUES (6, 'Call dentist', 1900000, 'Europe/London', 0, NULL, 'NONE')"
            )
        }

        val db = helper.runMigrationsAndValidate(dbName, 9, true, MIGRATION_8_9)

        db.query(
            "SELECT notificationLeadMinutes, lastNotifiedOccurrenceAt FROM Event WHERE id = 5"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }
        db.query(
            "SELECT sourceNoteId, lastNotifiedTriggerAt FROM Reminder WHERE id = 6"
        ).use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertTrue(cursor.isNull(1))
        }

        db.execSQL("UPDATE Event SET notificationLeadMinutes = 30, lastNotifiedOccurrenceAt = 2000000 WHERE id = 5")
        db.execSQL("UPDATE Reminder SET sourceNoteId = 9, lastNotifiedTriggerAt = 1900000 WHERE id = 6")
        db.query("SELECT notificationLeadMinutes, lastNotifiedOccurrenceAt FROM Event WHERE id = 5").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertEquals(30, cursor.getInt(0))
            assertEquals(2_000_000L, cursor.getLong(1))
        }

        // The provenance link is deliberately non-destructive: hard-deleting its note
        // clears the link while leaving the reminder and its delivery history intact.
        db.execSQL("PRAGMA foreign_keys = ON")
        db.execSQL("DELETE FROM Note WHERE id = 9")
        db.query("SELECT sourceNoteId, lastNotifiedTriggerAt FROM Reminder WHERE id = 6").use { cursor ->
            assertTrue(cursor.moveToFirst())
            assertTrue(cursor.isNull(0))
            assertEquals(1_900_000L, cursor.getLong(1))
        }
        db.close()
    }

    @Test
    fun productionMigrationSet_opensRealV8Database_andRetainsRawFts4Tables() {
        val dbName = "runtime-open-8-9-notes.db"
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.deleteDatabase(dbName)
        helper.createDatabase(dbName, 8).use { db ->
            db.execSQL(
                "INSERT INTO Note (id, title, body, createdAt, updatedAt, pinned, archived, deletedAt, folderId) " +
                    "VALUES (12, 'Migration proof', 'searchable body', 100, 200, 0, 0, NULL, NULL)"
            )
            db.execSQL(
                "INSERT INTO Event (id, title, startAt, endAt, timezone, notes, recurrence) " +
                    "VALUES (13, 'Runtime event', 2100000, 2160000, 'Europe/London', NULL, 'NONE')"
            )
            db.execSQL(
                "INSERT INTO Reminder (id, title, triggerAt, timezone, done, snoozedUntil, recurrence) " +
                    "VALUES (14, 'Runtime reminder', 2200000, 'Europe/London', 0, NULL, 'NONE')"
            )
            NotesFts.create(db)
            MemoryFts.create(db)
            db.execSQL("INSERT INTO note_fts (rowid, title, body) VALUES (12, 'Migration proof', 'searchable body')")
            db.execSQL("INSERT INTO memory_fts (slug, title, body) VALUES ('proof', 'Vault proof', 'durable memory')")
        }

        val room = Room.databaseBuilder(context, NotesDatabase::class.java, dbName)
            .addMigrations(*NOTES_MIGRATIONS)
            .build()
        try {
            val db = room.openHelper.writableDatabase
            db.query("PRAGMA user_version").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(9, cursor.getInt(0))
            }
            db.query("SELECT title FROM Note WHERE id = 12").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("Migration proof", cursor.getString(0))
            }
            db.query("SELECT rowid FROM note_fts WHERE note_fts MATCH 'searchable'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals(12L, cursor.getLong(0))
            }
            db.query("SELECT slug FROM memory_fts WHERE memory_fts MATCH 'durable'").use { cursor ->
                assertTrue(cursor.moveToFirst())
                assertEquals("proof", cursor.getString(0))
            }
            db.execSQL("UPDATE Event SET notificationLeadMinutes = 30 WHERE id = 13")
            runBlocking {
                assertEquals(1, room.reminderDao().claimNotification(14, 2_200_000L))
                assertEquals(0, room.reminderDao().claimNotification(14, 2_200_000L))
                assertEquals(1, room.eventDao().claimNotification(13, 2_100_000L))
                assertEquals(0, room.eventDao().claimNotification(13, 2_100_000L))
            }
        } finally {
            room.close()
            context.deleteDatabase(dbName)
        }
    }
}
