package com.fadghost.notesapp.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Full-text index for notes (PLAN.md §3/§6). The index is a *regular* FTS table
 * whose rows are written from Kotlin
 * ([com.fadghost.notesapp.data.repo.NotesRepository]). This lets us store
 * markdown-*stripped* text (so `#`, `**`, link URLs, etc. never pollute matches
 * or highlighted snippets) which triggers on the raw Note table could not do.
 *
 * FTS4, not FTS5: Android's framework SQLite does not compile the fts5 module
 * (fixed a first-launch crash: "no such module: fts5"). Our usage — table-name
 * MATCH with implicit-AND prefix tokens, Kotlin-side highlighting — is
 * identical under FTS4.
 *
 * Row identity: `rowid == Note.id`, so search joins straight back to Note for
 * pin/archive/trash filtering.
 */
object NotesFts {

    const val TABLE = "note_fts"

    /** Regular (content-owning) FTS4 table over stripped title/body. */
    private const val CREATE_TABLE =
        "CREATE VIRTUAL TABLE IF NOT EXISTS note_fts USING fts4(title, body)"

    /** Fresh installs: create the empty index (repository fills it on save). */
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
    }

    /**
     * v5→v6: rebuild the index as FTS4 (previous versions declared fts5, which
     * crashes at CREATE on framework SQLite). Re-index from Note; raw markdown
     * gets re-stripped on the next edit — acceptable one-time degradation.
     */
    fun migrateToFts4(db: SupportSQLiteDatabase) {
        db.execSQL("DROP TRIGGER IF EXISTS note_fts_ai")
        db.execSQL("DROP TRIGGER IF EXISTS note_fts_ad")
        db.execSQL("DROP TRIGGER IF EXISTS note_fts_au")
        db.execSQL("DROP TABLE IF EXISTS note_fts")
        db.execSQL(CREATE_TABLE)
        db.execSQL(
            "INSERT INTO note_fts(rowid, title, body) " +
                "SELECT id, title, body FROM Note WHERE deletedAt IS NULL"
        )
    }

    /**
     * Migrate M0's external-content table + triggers to the regular table
     * (v1→v2 step). Creates FTS4 directly: the original fts5 form crashed at
     * CREATE on every real device (framework SQLite lacks the module), so no
     * working install ever carried an fts5 index — safe to correct in place.
     */
    fun migrateFromExternalContent(db: SupportSQLiteDatabase) {
        migrateToFts4(db)
    }
}
