package com.fadghost.notesapp.data.audio

import java.io.File

/**
 * On-disk layout + orphan detection for voice attachments (PLAN.md §6 — "orphaned
 * files cleaned when trash purges; storage usage visible in settings"). Files live
 * under `filesDir/attachments/<noteId>/voice_<sessionId>/segment_NNN.m4a`, matching the per-note dir
 * convention [com.fadghost.notesapp.data.repo.NotesRepository] already purges on
 * hard-delete. Every function takes explicit [File] roots so the maths is testable
 * against a JUnit temp dir with no Android context.
 */
object AudioStorage {

    const val DIR = "attachments"
    const val SESSION_DIR = "voice_sessions"
    private val SAFE_SESSION_ID = Regex("[A-Za-z0-9_-]{1,80}")

    fun root(filesDir: File): File = File(filesDir, DIR)

    fun noteDir(filesDir: File, noteId: Long): File = File(root(filesDir), noteId.toString())

    /**
     * Directory for one recording inside a note. The session component is mandatory:
     * writing every capture directly into [noteDir] would restart at `segment_000.m4a`
     * and overwrite an older attachment for the same note.
     */
    fun recordingDir(filesDir: File, noteId: Long, sessionId: String): File {
        require(noteId > 0) { "A note recording needs a persisted note id" }
        requireSafeSessionId(sessionId)
        return File(noteDir(filesDir, noteId), "voice_$sessionId")
    }

    /** Durable session metadata / transient audio root for captures not owned by a Note. */
    fun sessionsRoot(filesDir: File): File = File(filesDir, SESSION_DIR)

    fun sessionDir(filesDir: File, sessionId: String): File {
        requireSafeSessionId(sessionId)
        return File(sessionsRoot(filesDir), sessionId)
    }

    fun transientAudioDir(filesDir: File, sessionId: String): File =
        File(sessionDir(filesDir, sessionId), "audio")

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
    fun pruneEmptyDirectories(root: File, deleteRoot: Boolean = true): Int {
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
