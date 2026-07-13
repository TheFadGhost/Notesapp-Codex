package com.fadghost.notesapp.data.audio

import com.fadghost.notesapp.data.db.entity.AudioAttachment
import com.fadghost.notesapp.data.repo.NotesRepository
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Pure idempotency predicate for voice commits (audit M2), split out so it is
 * unit-testable without Room/Context.
 */
object VoiceCommitLogic {
    /**
     * The already-committed attachment whose segment set equals [segmentPaths], or null
     * if these segments have not been committed yet. A WorkManager retry re-runs the
     * commit with the same paths; matching lets the caller skip a duplicate append.
     */
    fun existingCommit(existing: List<AudioAttachment>, segmentPaths: List<String>): AudioAttachment? {
        if (segmentPaths.isEmpty()) return null
        val want = segmentPaths.toSet()
        return existing.firstOrNull { it.segments.toSet() == want }
    }
}

/**
 * Commits a finished voice transcript into a note (PLAN.md §5 result flow): appends
 * the transcript text to the note body and records the audio attachment anchored at
 * the transcript line start (the editor draws the circular chip there, PLAN.md §2.3).
 * Shared by the recording view-model (capture-sheet → new note) and the offline
 * [com.fadghost.notesapp.data.ai.work.TranscribeQueueWorker] so both produce an
 * identical body/attachment pairing.
 */
@Singleton
class VoiceCommit @Inject constructor(
    private val notes: NotesRepository,
    private val attachments: AudioAttachmentRepository
) {
    data class Committed(val noteId: Long, val transcriptStart: Int, val transcriptEnd: Int)

    /** Append [transcript] to note [noteId]'s body and record its audio [segments]. */
    suspend fun appendTranscript(
        noteId: Long,
        transcript: String,
        segments: List<RecordedSegment>,
        now: Long = System.currentTimeMillis()
    ): Committed? {
        val note = notes.getNote(noteId) ?: return null
        // Idempotency (audit M2): if these exact segments were already committed (a prior
        // retry finished after transcription), return the existing anchor instead of
        // appending the transcript and recording the attachment a second time.
        VoiceCommitLogic.existingCommit(attachments.forNote(noteId), segments.map { it.path })?.let {
            return Committed(noteId, it.transcriptStart, it.transcriptEnd)
        }
        val sep = if (note.body.isBlank()) "" else "\n\n"
        val start = note.body.length + sep.length
        val end = start + transcript.length
        notes.saveNote(note.copy(body = note.body + sep + transcript, updatedAt = now))
        if (segments.isNotEmpty()) {
            attachments.record(noteId, segments, transcriptStart = start, transcriptEnd = end, now = now)
        }
        return Committed(noteId, start, end)
    }

    /**
     * Attach captured audio to a note whose body was produced elsewhere (the ramble rewrite
     * pipeline). Segment-set matching makes a WorkManager retry safe if the row was inserted
     * before the worker process died.
     */
    suspend fun attachOnly(
        noteId: Long,
        segments: List<RecordedSegment>,
        transcriptStart: Int = 0,
        transcriptEnd: Int = 0,
        now: Long = System.currentTimeMillis()
    ): Committed? {
        notes.getNote(noteId) ?: return null
        if (segments.isEmpty()) return Committed(noteId, transcriptStart, transcriptEnd)
        VoiceCommitLogic.existingCommit(attachments.forNote(noteId), segments.map { it.path })?.let {
            return Committed(noteId, it.transcriptStart, it.transcriptEnd)
        }
        attachments.record(noteId, segments, transcriptStart, transcriptEnd, now)
        return Committed(noteId, transcriptStart, transcriptEnd)
    }
}
