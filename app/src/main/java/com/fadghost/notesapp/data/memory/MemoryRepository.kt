package com.fadghost.notesapp.data.memory

import android.content.Context
import androidx.room.withTransaction
import com.fadghost.notesapp.data.db.NotesDatabase
import com.fadghost.notesapp.data.db.dao.MemoryDao
import com.fadghost.notesapp.data.db.entity.MemoryEntry
import com.fadghost.notesapp.data.db.entity.MemoryLink
import com.fadghost.notesapp.util.FtsQuery
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The Folio memory vault repository (V3-PROMPTS.md §1.1). Files under `filesDir/memory/`
 * are the SOURCE OF TRUTH; the Room `memory_entries` + `memory_links` mirror and the raw
 * `memory_fts` FTS4 index are a queryable projection kept in lock-step with the files.
 *
 * Every write runs off the main thread ([Dispatchers.IO]); the mirror + links + FTS parts
 * commit in one Room transaction so a kill can never leave the mirror half-written. If a
 * kill lands between the file write and the mirror commit, [reconcile] rebuilds the mirror
 * from the files at next app start (checksum mismatch). Memory writes are ALWAYS
 * user-triggered and undoable — nothing here is called automatically from note edits.
 */
@Singleton
class MemoryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    private val db: NotesDatabase,
    private val dao: MemoryDao,
    private val prefs: MemoryPreferences
) {
    private val filesDir: File get() = context.filesDir

    val entries: Flow<List<MemoryEntry>> = dao.observeAll()
    val count: Flow<Int> = dao.observeCount()
    val enabled: Flow<Boolean> = prefs.enabled
    val saveCount: Flow<Int> = prefs.saveCount

    /** Snapshot of the vault state a write replaced, so the write can be undone. */
    data class WriteResult(
        val slugs: List<String>,
        /** Per written slug: the model it replaced, or null if the slug was brand-new. */
        val previous: Map<String, MemoryEntryModel?>
    )

    // --- Write / accept ---------------------------------------------------------

    /**
     * Persist accepted entries (the "Add to memory" confirm-card accept). Writes the entry
     * files first (truth), then mirrors rows + links + FTS in one transaction. Returns a
     * [WriteResult] carrying what each slug replaced so the caller can offer Undo. `op:update`
     * is a plain slug-keyed overwrite — the model already carries the merged body from P1.
     */
    suspend fun writeEntries(models: List<MemoryEntryModel>): WriteResult = withContext(Dispatchers.IO) {
        val previous = models.associate { it.slug to MemoryVault.readEntry(filesDir, it.slug) }
        // Preserve the original `created` date when an entry is being updated (op:update);
        // only `updated` moves forward. New slugs keep the model's created stamp.
        val adjusted = models.map { m -> previous[m.slug]?.let { m.copy(created = it.created) } ?: m }
        MemoryVault.writeEntries(filesDir, adjusted)
        db.withTransaction {
            for (m in adjusted) mirror(m)
        }
        prefs.setChecksum(MemoryVault.checksum(filesDir))
        WriteResult(adjusted.map { it.slug }, previous)
    }

    /** The current `index.md` text (fed to P1 for dedup routing). */
    suspend fun currentIndex(): String = withContext(Dispatchers.IO) { MemoryVault.readIndex(filesDir) }

    /** Undo a [writeEntries]: restore each replaced model, or delete a slug that was new. */
    suspend fun undoWrite(result: WriteResult) = withContext(Dispatchers.IO) {
        val restore = result.previous.values.filterNotNull()
        val remove = result.slugs.filter { result.previous[it] == null }
        if (restore.isNotEmpty()) MemoryVault.writeEntries(filesDir, restore)
        for (slug in remove) MemoryVault.deleteEntry(filesDir, slug)
        db.withTransaction {
            for (m in restore) mirror(m)
            for (slug in remove) unmirror(slug)
        }
        prefs.setChecksum(MemoryVault.checksum(filesDir))
    }

    /** Edit-in-place of a single existing entry (Settings / M-C entry sheet). */
    suspend fun putEntry(model: MemoryEntryModel): WriteResult = writeEntries(listOf(model))

    /** Delete one entry (file + mirror + links + FTS). Returns it for an undo. */
    suspend fun deleteEntry(slug: String): MemoryEntryModel? = withContext(Dispatchers.IO) {
        val prev = MemoryVault.readEntry(filesDir, slug)
        MemoryVault.deleteEntry(filesDir, slug)
        db.withTransaction { unmirror(slug) }
        prefs.setChecksum(MemoryVault.checksum(filesDir))
        prev
    }

    // --- Wipe (Settings) --------------------------------------------------------

    /** Every entry on disk — the snapshot captured before a wipe, for the undo window. */
    suspend fun snapshot(): List<MemoryEntryModel> = withContext(Dispatchers.IO) {
        MemoryVault.readAllEntries(filesDir)
    }

    suspend fun wipeAll() = withContext(Dispatchers.IO) {
        MemoryVault.wipe(filesDir)
        db.withTransaction {
            dao.clearEntries()
            dao.clearLinks()
            ftsClear()
        }
        prefs.setChecksum(MemoryVault.checksum(filesDir))
    }

    /** Undo a wipe: rewrite every snapshotted entry and rebuild the mirror. */
    suspend fun restoreSnapshot(models: List<MemoryEntryModel>) = withContext(Dispatchers.IO) {
        if (models.isEmpty()) return@withContext
        MemoryVault.writeEntries(filesDir, models)
        rebuildMirror()
    }

    // --- Backup -----------------------------------------------------------------

    suspend fun exportBytes(): Map<String, ByteArray> = withContext(Dispatchers.IO) {
        MemoryVault.exportBytes(filesDir)
    }

    /** Restore vault files from a backup, then rebuild the Room/FTS mirror. */
    suspend fun importFiles(
        files: Map<String, ByteArray>,
        replace: Boolean
    ) = withContext(Dispatchers.IO) {
        MemoryVault.importBytes(filesDir, files, replace)
        rebuildMirror()
    }

    // --- Search -----------------------------------------------------------------

    /** FTS candidate entries for a query (retrieval step 1). Empty query → most-recent list. */
    suspend fun search(query: String): List<MemoryEntry> = withContext(Dispatchers.IO) {
        val match = FtsQuery.build(query) ?: return@withContext dao.all()
        runCatching { dao.search(match) }.getOrDefault(emptyList())
    }

    /** Load an ordered, bounded set selected by the Ask retrieval router. */
    suspend fun entriesBySlugs(slugs: List<String>): List<MemoryEntry> = withContext(Dispatchers.IO) {
        slugs.distinct().take(8).mapNotNull { dao.byId(it) }
    }

    // --- Reconciliation (app start, background) ---------------------------------

    /**
     * Rebuild the mirror from the files only when they have drifted from what the mirror
     * last saw (checksum mismatch), or when files exist but the mirror is empty (a kill
     * landed mid-write). Cheap and idempotent; safe to run on every cold start.
     */
    suspend fun reconcile() = withContext(Dispatchers.IO) {
        val current = MemoryVault.checksum(filesDir)
        val stored = prefs.lastChecksum()
        val mirrorEmpty = dao.count() == 0
        val filesExist = MemoryVault.readAllEntries(filesDir).isNotEmpty()
        if (current != stored || (mirrorEmpty && filesExist)) {
            rebuildMirror()
        }
    }

    /** Clear + repopulate the whole mirror (rows + links + FTS) from the entry files. */
    suspend fun rebuildMirror() = withContext(Dispatchers.IO) {
        val models = MemoryVault.readAllEntries(filesDir)
        db.withTransaction {
            dao.clearEntries()
            dao.clearLinks()
            ftsClear()
            for (m in models) mirror(m)
        }
        prefs.setChecksum(MemoryVault.checksum(filesDir))
    }

    // --- Settings ---------------------------------------------------------------

    suspend fun setEnabled(value: Boolean) = prefs.setEnabled(value)
    suspend fun enabledNow(): Boolean = prefs.enabledNow()

    /** Bump the lifetime save counter; the returned total drives the hero-line throttle. */
    suspend fun bumpSaveCount(by: Int): Int = prefs.bumpSaveCount(by)

    // --- Internal: mirror one model (must run inside a Room transaction) --------

    private suspend fun mirror(m: MemoryEntryModel) {
        dao.upsert(
            MemoryEntry(
                slug = m.slug,
                title = m.title,
                type = m.type,
                tags = m.tags.joinToString(","),
                body = m.body,
                source = m.source,
                created = m.created,
                updated = m.updated
            )
        )
        dao.deleteOutgoingLinks(m.slug)
        if (m.links.isNotEmpty()) {
            dao.upsertLinks(m.links.filter { it != m.slug }.map { MemoryLink(m.slug, it) })
        }
        ftsUpsert(m.slug, m.title, m.body)
    }

    private suspend fun unmirror(slug: String) {
        dao.deleteEntry(slug)
        dao.deleteLinksFor(slug)
        ftsDelete(slug)
    }

    // --- Internal: raw FTS4 writes (same connection as the enclosing txn) -------

    private fun ftsUpsert(slug: String, title: String, body: String) {
        val wdb = db.openHelper.writableDatabase
        wdb.execSQL("DELETE FROM memory_fts WHERE slug = ?", arrayOf<Any>(slug))
        wdb.execSQL(
            "INSERT INTO memory_fts(slug, title, body) VALUES (?, ?, ?)",
            arrayOf<Any>(slug, title, body)
        )
    }

    private fun ftsDelete(slug: String) {
        db.openHelper.writableDatabase.execSQL("DELETE FROM memory_fts WHERE slug = ?", arrayOf<Any>(slug))
    }

    private fun ftsClear() {
        db.openHelper.writableDatabase.execSQL("DELETE FROM memory_fts")
    }
}
