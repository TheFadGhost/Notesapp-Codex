package com.fadghost.notesapp.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Full-text index for memory entries (V3-PROMPTS.md §1.1 retrieval step 1 — "local FTS
 * over entry bodies+titles → candidate slugs"). Exactly the [NotesFts] pattern: a regular
 * (content-owning) FTS4 table whose rows are written from Kotlin, kept OUT of Room's entity
 * set so it never touches the exported schema (Room validates only `memory_entries` /
 * `memory_links`), and queried through `@RawQuery`.
 *
 * FTS4, not FTS5: Android's framework SQLite has no fts5 module. Rows are keyed by the
 * entry `slug` column (memory's PK is a TEXT slug, not an integer rowid), so search joins
 * back to `memory_entries` on `slug`.
 */
object MemoryFts {

    const val TABLE = "memory_fts"

    private const val CREATE_TABLE =
        "CREATE VIRTUAL TABLE IF NOT EXISTS memory_fts USING fts4(slug, title, body)"

    /** Fresh installs (callback.onCreate) + the v7→v8 migration both call this. */
    fun create(db: SupportSQLiteDatabase) {
        db.execSQL(CREATE_TABLE)
    }
}
