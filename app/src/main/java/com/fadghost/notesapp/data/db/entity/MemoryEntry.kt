package com.fadghost.notesapp.data.db.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Room MIRROR of a memory-vault entry file (V3-PROMPTS.md §1.1, schema v8, M-B). The
 * markdown files under `filesDir/memory/` are the source of truth; this table is a
 * queryable projection rebuilt from those files on checksum mismatch at app start.
 *
 * Columns per the milestone spec: slug PK, title, type, tags CSV, body, source, created,
 * updated. The `hook` lives in `index.md` (and the entry front-matter) — retrieval routing
 * reads the index, not this table — so it is intentionally NOT mirrored here. Edges live in
 * [MemoryLink]; full-text lives in the raw `memory_fts` FTS4 table (not a Room entity).
 */
@Entity(
    tableName = "memory_entries",
    indices = [Index("type"), Index("updated")]
)
data class MemoryEntry(
    @PrimaryKey val slug: String,
    val title: String,
    /** One of MemoryFormat.TYPES. */
    val type: String,
    /** Comma-separated lowercase tags (max 5). */
    val tags: String,
    val body: String,
    /** `note:<id>` | `chat` | `manual`. */
    val source: String,
    /** ISO date `yyyy-MM-dd`. */
    val created: String,
    val updated: String
)
