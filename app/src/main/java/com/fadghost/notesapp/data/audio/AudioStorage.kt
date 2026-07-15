package com.fadghost.notesapp.data.audio

import java.io.File

/**
 * On-disk layout + orphan detection for voice attachments (PLAN.md §6 — "orphaned
 * files cleaned when trash purges; storage usage visible in settings"). Files live
 * under `filesDir/attachments/<noteId>/voice_sessions/<sessionId>/segment_NNN.m4a`,
 * matching the per-note directory convention that NotesRepository already purges on
 * hard-delete. Every function takes explicit [File] roots so the maths is testable
 * against a JUnit temp dir with no Android context.
 */
object AudioStorage {

    const val DIR = "attachments"
    const val SESSION_DIR = "voice_sessions"
    private val SAFE_SESSION_ID = Regex("[A-Za-z0-9._-]{1,80}")

    fun root(filesDir: File): File = File(filesDir, DIR)

    fun noteDir(filesDir: File, noteId: Long): File = File(root(filesDir), noteId.toString())

    /**
     * Resolve either a v4 note-owned recording session or a Codex durable global
     * session. Both historical APIs used `(File, String)`, so numeric directories
     * directly beneath `attachments` are treated as note directories.
     */
    fun sessionDir(baseDir: File, sessionId: String): File {
        requireSafeSessionId(sessionId)
        val isNoteDir = baseDir.name.toLongOrNull() != null && baseDir.parentFile?.name == DIR
        return if (isNoteDir) File(File(baseDir, SESSION_DIR), sessionId)
        else File(sessionsRoot(baseDir), sessionId)
    }

    /** Collision-free note-owned directory used by both recording implementations. */
    fun recordingDir(filesDir: File, noteId: Long, sessionId: String): File {
        require(noteId > 0) { "A note recording needs a persisted note id" }
        return sessionDir(noteDir(filesDir, noteId), sessionId)
    }

    /** Durable Codex ramble metadata/transient-audio root outside note attachments. */
    fun sessionsRoot(filesDir: File): File = File(filesDir, SESSION_DIR)

    fun transientAudioDir(filesDir: File, sessionId: String): File =
        File(sessionDir(filesDir, sessionId), "audio")

    fun pruneEmptySessionParents(sessionDir: File) {
        var current: File? = sessionDir
        repeat(3) {
            val dir = current ?: return
            if (dir.name == DIR || dir.listFiles()?.isNotEmpty() == true) return
            runCatching { dir.delete() }
            current = dir.parentFile
        }
    }


    /** Segment file for [index] inside [noteDir], creating parent dirs as needed. */
    fun segmentFile(noteDir: File, index: Int): File {
        if (!noteDir.exists()) noteDir.mkdirs()
        return File(noteDir, AudioSegments.fileName(index))
    }

    /** Total bytes of every regular file under [root] (recursive). Missing root -> 0. */
    fun totalBytes(root: File): Long {
        if (!root.exists()) return 0L
        return root.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }

    /** Total bytes for a single note's attachment dir. */
    fun noteBytes(filesDir: File, noteId: Long): Long = totalBytes(noteDir(filesDir, noteId))

    /**
     * Audio files under [root] not referenced by any live attachment. [referenced] is
     * the set of absolute paths recorded in the DB; any on-disk audio file whose path
     * is absent is an orphan (e.g. a discarded recording or a purged note's leftovers).
     */
    fun findOrphans(root: File, referenced: Set<String>): List<File> {
        if (!root.exists()) return emptyList()
        val live = referenced.map { File(it).absolutePath }.toHashSet()
        return root.walkTopDown()
            .filter { it.isFile }
            .filter { it.absolutePath !in live }
            .toList()
    }

    /** Remove empty nested session/note directories bottom-up after their audio is deleted. */
    fun pruneEmptyDirectories(root: File, deleteRoot: Boolean = false): Int {
        if (!root.isDirectory) return 0
        var removed = 0
        root.walkBottomUp()
            .filter { it.isDirectory && (deleteRoot || it != root) }
            .forEach { directory ->
                if (directory.listFiles()?.isEmpty() == true && directory.delete()) removed++
            }
        return removed
    }

    /** Human-readable size for the chip popover / storage row (e.g. "1.4 MB"). */
    fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 KB"
        val kb = bytes / 1024.0
        if (kb < 1024) return "%.0f KB".format(kb)
        val mb = kb / 1024.0
        return "%.1f MB".format(mb)
    }

    private fun requireSafeSessionId(sessionId: String) {
        require(SAFE_SESSION_ID.matches(sessionId)) { "Unsafe voice session id" }
    }
}
