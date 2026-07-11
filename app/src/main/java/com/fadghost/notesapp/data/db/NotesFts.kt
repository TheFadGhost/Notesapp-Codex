package com.fadghost.notesapp.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * FTS5 external-content table for [Note] plus the triggers that keep it in sync
 * (PLAN.md §3/§6). Room has no @Fts5 entity annotation, so the virtual table and
 * triggers are created imperatively — on fresh installs via the database callback
 * and, in future, inside migrations. Search runs through NoteDao's @RawQuery.
 */
object NotesFts {

    /** external-content FTS5 table mirroring Note(title, body), keyed by Note.id. */
    private const val CREATE_TABLE =
        "CREATE VIRTUAL TABLE IF NOT EXISTS note_fts USING fts5(" +
            "title, body, content='Note', content_rowid='id')"

    private const val TRIGGER_INSERT =
        "CREATE TRIGGER IF NOT EXISTS note_fts_ai AFTER INSERT ON Note BEGIN " +
            "INSERT INTO note_fts(rowid, title, body) VALUES (new.id, new.title, new.body); END"

    private const val TRIGGER_DELETE =
        "CREATE TRIGGER IF NOT EXISTS note_fts_ad AFTER DELETE ON Note BEGIN " +
            "INSERT INTO note_fts(note_fts, rowid, title, body) " +
            "VALUES('delete', old.id, old.title, old.body); END"

    private const val TRIGGER_UPDATE =
        "CREATE TRIGGER IF NOT EXISTS note_fts_au AFTER UPDATE ON Note BEGIN " +
            "INSERT INTO note_fts(note_fts, rowid, title, body) " +
            "VALUES('delete', old.id, old.title, old.body); " +
            "INSERT INTO note_fts(rowid, title, body) VALUES (new.id, new.title, new.body); END"

    fun create(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
        db.execSQL(TRIGGER_INSERT)
        db.execSQL(TRIGGER_DELETE)
        db.execSQL(TRIGGER_UPDATE)
        // Rebuild in case rows already exist (no-op on a fresh DB).
        db.execSQL("INSERT INTO note_fts(note_fts) VALUES('rebuild')")
    }
}
