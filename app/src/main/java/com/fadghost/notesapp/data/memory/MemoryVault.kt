package com.fadghost.notesapp.data.memory

import java.io.File
import java.security.MessageDigest

/**
 * On-disk markdown vault (V3-PROMPTS.md §1.1) — the SOURCE OF TRUTH for memory:
 *
 * ```
 * files/memory/
 *   index.md            # derived index, one line per entry
 *   entries/<slug>.md   # one atomic entry per file
 * ```
 *
 * Obsidian-compatible plain markdown, exportable in backups. Every function takes an
 * explicit [File] root so the maths is testable against a JUnit temp dir with no Android
 * context (the repository passes `context.filesDir`).
 */
object MemoryVault {

    const val DIR = "memory"
    const val ENTRIES_DIR = "entries"
    const val INDEX_FILE = "index.md"

    /** ZIP path prefix used in backups (parallels `attachments/`). */
    const val ZIP_PREFIX = "memory/"

    fun root(filesDir: File): File = File(filesDir, DIR)
    fun entriesRoot(filesDir: File): File = File(root(filesDir), ENTRIES_DIR)
    fun indexFile(filesDir: File): File = File(root(filesDir), INDEX_FILE)
    fun entryFile(filesDir: File, slug: String): File = File(entriesRoot(filesDir), "$slug.md")

    private fun ensureDirs(filesDir: File) {
        entriesRoot(filesDir).mkdirs()
    }

    /** Write one entry file + refresh `index.md` from the current on-disk entries. */
    fun writeEntry(filesDir: File, entry: MemoryEntryModel) {
        ensureDirs(filesDir)
        entryFile(filesDir, entry.slug).writeText(entry.toMarkdown())
        refreshIndex(filesDir)
    }

    /** Write several entries then refresh the index once (batch accept). */
    fun writeEntries(filesDir: File, entries: List<MemoryEntryModel>) {
        ensureDirs(filesDir)
        for (e in entries) entryFile(filesDir, e.slug).writeText(e.toMarkdown())
        refreshIndex(filesDir)
    }

    /** Delete one entry file + refresh the index. */
    fun deleteEntry(filesDir: File, slug: String) {
        entryFile(filesDir, slug).delete()
        refreshIndex(filesDir)
    }

    fun readEntry(filesDir: File, slug: String): MemoryEntryModel? =
        entryFile(filesDir, slug).takeIf { it.exists() }
            ?.let { runCatching { MemoryEntryModel.parse(it.readText()) }.getOrNull() }

    /** Every parseable entry on disk, sorted by slug. */
    fun readAllEntries(filesDir: File): List<MemoryEntryModel> {
        val dir = entriesRoot(filesDir)
        if (!dir.exists()) return emptyList()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.mapNotNull { runCatching { MemoryEntryModel.parse(it.readText()) }.getOrNull() }
            ?.sortedBy { it.slug }
            ?: emptyList()
    }

    /** Regenerate `index.md` from the entry files (the index is a derived cache). */
    fun refreshIndex(filesDir: File) {
        ensureDirs(filesDir)
        indexFile(filesDir).writeText(MemoryFormat.renderIndex(readAllEntries(filesDir)))
    }

    fun readIndex(filesDir: File): String =
        indexFile(filesDir).takeIf { it.exists() }?.readText().orEmpty()

    /** Wipe the whole vault (files) — used by Settings "wipe memory". */
    fun wipe(filesDir: File) {
        root(filesDir).deleteRecursively()
    }

    /**
     * A checksum over every vault file (index + entries), so app-start can detect drift
     * between the files (truth) and the Room mirror and rebuild only when they differ.
     */
    fun checksum(filesDir: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        val idx = indexFile(filesDir)
        if (idx.exists()) { md.update("index".toByteArray()); md.update(idx.readBytes()) }
        val dir = entriesRoot(filesDir)
        dir.listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?.forEach { md.update(it.name.toByteArray()); md.update(it.readBytes()) }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** ZIP-relative path -> bytes for every vault file (backup export). */
    fun exportBytes(filesDir: File): Map<String, ByteArray> {
        val out = LinkedHashMap<String, ByteArray>()
        val idx = indexFile(filesDir)
        if (idx.exists()) out["${ZIP_PREFIX}$INDEX_FILE"] = idx.readBytes()
        entriesRoot(filesDir).listFiles { f -> f.isFile && f.name.endsWith(".md") }
            ?.sortedBy { it.name }
            ?.forEach { out["${ZIP_PREFIX}$ENTRIES_DIR/${it.name}"] = it.readBytes() }
        return out
    }

    /** Write vault files back from a backup (keys are ZIP paths like `memory/index.md`). */
    fun importBytes(filesDir: File, files: Map<String, ByteArray>) {
        ensureDirs(filesDir)
        for ((path, bytes) in files) {
            if (!path.startsWith(ZIP_PREFIX)) continue
            val rel = path.removePrefix(ZIP_PREFIX)
            val target = when {
                rel == INDEX_FILE -> indexFile(filesDir)
                rel.startsWith("$ENTRIES_DIR/") -> File(entriesRoot(filesDir), File(rel).name)
                else -> continue
            }
            runCatching { target.writeBytes(bytes) }
        }
    }
}
