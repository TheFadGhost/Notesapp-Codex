package com.fadghost.notesapp

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.fadghost.notesapp.data.db.MIGRATION_6_7
import com.fadghost.notesapp.data.db.MIGRATION_7_8
import com.fadghost.notesapp.data.db.NotesDatabase
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

        // Migrate to v8; Room validates the mirror tables against schemas/8.json.
        val db = helper.runMigrationsAndValidate(testDb, 8, true, MIGRATION_7_8)

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
    }
}
