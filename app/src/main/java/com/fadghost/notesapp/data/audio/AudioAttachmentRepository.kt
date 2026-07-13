package com.fadghost.notesapp.data.audio

import android.content.Context
import com.fadghost.notesapp.data.db.dao.AudioAttachmentDao
import com.fadghost.notesapp.data.db.entity.AudioAttachment
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Owns the disk<->DB relationship for voice attachments (PLAN.md §5/§6): recording a
 * segment set, deleting a single attachment (row + its files), reporting per-note and
 * total sizes, and sweeping orphaned audio files whose row is gone (called from the
 * trash-purge worker). File maths lives in the pure [AudioStorage] helpers.
 */
@Singleton
class AudioAttachmentRepository @Inject constructor(
    private val dao: AudioAttachmentDao,
    @ApplicationContext private val context: Context
) {
    fun observeForNote(noteId: Long): Flow<List<AudioAttachment>> = dao.observeForNote(noteId)
    fun observeTotalBytes(): Flow<Long> = dao.observeTotalBytes()

    /** One-shot read of a note's attachments (drives the M2 commit idempotency check). */
    suspend fun forNote(noteId: Long): List<AudioAttachment> = dao.forNote(noteId)

    fun noteDir(noteId: Long): File = AudioStorage.noteDir(context.filesDir, noteId)

    /** A collision-free directory for one recording session inside a note. */
    fun recordingDir(noteId: Long, sessionId: String): File =
        AudioStorage.recordingDir(context.filesDir, noteId, sessionId)

    /**
     * Persist an attachment for [noteId] built from [segments] (already written to
     * disk). Returns the new row id. [transcriptStart]/[transcriptEnd] anchor the chip.
     */
    suspend fun record(
        noteId: Long,
        segments: List<RecordedSegment>,
        transcriptStart: Int,
        transcriptEnd: Int,
        now: Long = System.currentTimeMillis()
    ): Long {
        if (segments.isEmpty()) return 0L
        val paths = segments.map { it.path }
        val size = paths.sumOf { runCatching { File(it).length() }.getOrDefault(0L) }
        return dao.insert(
            AudioAttachment(
                noteId = noteId,
                filePath = paths.first(),
                segmentPaths = paths.joinToString("\n"),
                durationMs = segments.sumOf { it.durationMs },
                sizeBytes = size,
                createdAt = now,
                transcriptStart = transcriptStart,
                transcriptEnd = transcriptEnd
            )
        )
    }

    /** Delete a single attachment: its files then its row (chip popover "delete audio"). */
    suspend fun delete(id: Long) {
        val row = dao.byId(id) ?: return
        row.segments.forEach { runCatching { File(it).delete() } }
        dao.deleteById(id)
        pruneEmptyDir(row.noteId)
    }

    /** Bytes used by one note's attachments. */
    fun noteBytes(noteId: Long): Long = AudioStorage.noteBytes(context.filesDir, noteId)

    /**
     * Delete audio files under the attachments root that no live row references
     * (PLAN.md §6). Returns how many files were removed. Safe to call repeatedly.
     */
    suspend fun sweepOrphans(): Int {
        val referenced = dao.all().flatMap { it.segments }.toSet()
        val root = AudioStorage.root(context.filesDir)
        // Only the recorder's own `.m4a` files are ours to sweep. Image/file attachments
        // (M-A) share this per-note root; their orphans belong to AttachmentRepository.
        val orphans = AudioStorage.findOrphans(root, referenced)
            .filter { it.extension.equals("m4a", ignoreCase = true) }
        orphans.forEach { runCatching { it.delete() } }
        // Tidy the nested `voice_<session>` directories as well as now-empty note dirs.
        runCatching { AudioStorage.pruneEmptyDirectories(root, deleteRoot = false) }
        return orphans.size
    }

    private fun pruneEmptyDir(noteId: Long) {
        runCatching { AudioStorage.pruneEmptyDirectories(noteDir(noteId)) }
    }
}
