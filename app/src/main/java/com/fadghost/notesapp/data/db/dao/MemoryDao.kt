package com.fadghost.notesapp.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SimpleSQLiteQuery
import com.fadghost.notesapp.data.db.entity.MemoryEntry
import com.fadghost.notesapp.data.db.entity.MemoryLink
import kotlinx.coroutines.flow.Flow

/**
 * Reads/writes for the memory MIRROR (schema v8, M-B). Files are the source of truth; the
 * repository funnels file writes + FTS writes + these rows together inside one transaction
 * so the three stay consistent. `op:update` from P1 is a plain slug-keyed REPLACE upsert
 * (V3-PROMPTS.md §1.2 rule 4 — dedup by slug). Search joins the raw `memory_fts` FTS4
 * table back on `slug` via [searchRaw].
 */
@Dao
interface MemoryDao {

    // --- Entries ---------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entry: MemoryEntry)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entries: List<MemoryEntry>)

    @Query("SELECT * FROM memory_entries ORDER BY updated DESC, slug")
    fun observeAll(): Flow<List<MemoryEntry>>

    @Query("SELECT * FROM memory_entries ORDER BY updated DESC, slug")
    suspend fun all(): List<MemoryEntry>

    @Query("SELECT * FROM memory_entries WHERE slug = :slug")
    suspend fun byId(slug: String): MemoryEntry?

    @Query("SELECT COUNT(*) FROM memory_entries")
    fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM memory_entries")
    suspend fun count(): Int

    @Query("DELETE FROM memory_entries WHERE slug = :slug")
    suspend fun deleteEntry(slug: String)

    @Query("DELETE FROM memory_entries")
    suspend fun clearEntries()

    // --- Links -----------------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertLinks(links: List<MemoryLink>)

    @Query("SELECT * FROM memory_links")
    suspend fun allLinks(): List<MemoryLink>

    @Query("SELECT * FROM memory_links")
    fun observeLinks(): Flow<List<MemoryLink>>

    /** Clear only a slug's OUTGOING edges before rewriting them (keeps incoming edges). */
    @Query("DELETE FROM memory_links WHERE fromSlug = :slug")
    suspend fun deleteOutgoingLinks(slug: String)

    /** Clear every edge touching a slug (entry deleted entirely). */
    @Query("DELETE FROM memory_links WHERE fromSlug = :slug OR toSlug = :slug")
    suspend fun deleteLinksFor(slug: String)

    @Query("DELETE FROM memory_links")
    suspend fun clearLinks()

    // --- Full-text search (raw FTS4 table joined back on slug) -----------------

    @RawQuery(observedEntities = [MemoryEntry::class])
    suspend fun searchRaw(query: SimpleSQLiteQuery): List<MemoryEntry>

    /** Entries whose title/body match an FTS4 MATCH expression, most-recent first. */
    suspend fun search(match: String): List<MemoryEntry> = searchRaw(
        SimpleSQLiteQuery(
            "SELECT e.* FROM memory_entries e JOIN memory_fts f ON e.slug = f.slug " +
                "WHERE f.memory_fts MATCH ? ORDER BY e.updated DESC, e.slug",
            arrayOf(match)
        )
    )
}
