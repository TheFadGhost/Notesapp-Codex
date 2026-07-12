package com.fadghost.notesapp

import com.fadghost.notesapp.data.audio.VoiceCommitLogic
import com.fadghost.notesapp.data.db.entity.AudioAttachment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Transcription commit idempotency (audit M2). A WorkManager retry re-runs the commit
 * with the same segment paths; it must append the transcript + attachment exactly once
 * so retries don't duplicate text or re-bill STT. Drives the real [VoiceCommitLogic]
 * predicate that both the worker and [com.fadghost.notesapp.data.audio.VoiceCommit] use.
 */
class VoiceCommitIdempotencyTest {

    private fun attachment(noteId: Long, paths: List<String>, anchor: Int) = AudioAttachment(
        noteId = noteId,
        filePath = paths.first(),
        segmentPaths = paths.joinToString("\n"),
        durationMs = 0,
        sizeBytes = 0,
        createdAt = 0,
        transcriptStart = anchor,
        transcriptEnd = anchor
    )

    @Test fun double_run_appends_once() {
        val committed = mutableListOf<AudioAttachment>()
        var appends = 0
        val paths = listOf("/audio/1/segment_000.m4a", "/audio/1/segment_001.m4a")

        // Simulate the commit twice (original run + a retry) with the same segments.
        repeat(2) {
            if (VoiceCommitLogic.existingCommit(committed, paths) == null) {
                appends++
                committed += attachment(noteId = 1, paths = paths, anchor = appends)
            }
        }

        assertEquals(1, appends)
        assertEquals(1, committed.size)
    }

    @Test fun different_segments_still_append() {
        val committed = mutableListOf(attachment(1, listOf("/audio/1/segment_000.m4a"), anchor = 1))
        // A second, distinct ramble into the same note is not a duplicate.
        assertNull(VoiceCommitLogic.existingCommit(committed, listOf("/audio/1/segment_009.m4a")))
    }

    @Test fun matching_segments_return_existing_anchor() {
        val paths = listOf("/audio/2/segment_000.m4a", "/audio/2/segment_001.m4a")
        val committed = listOf(attachment(2, paths, anchor = 42))
        val hit = VoiceCommitLogic.existingCommit(committed, paths)
        assertNotNull(hit)
        assertEquals(42, hit!!.transcriptStart)
    }

    @Test fun empty_segments_never_match() {
        val committed = listOf(attachment(1, listOf("/audio/1/segment_000.m4a"), anchor = 1))
        assertNull(VoiceCommitLogic.existingCommit(committed, emptyList()))
    }
}
