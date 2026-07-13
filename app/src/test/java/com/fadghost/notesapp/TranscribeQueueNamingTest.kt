package com.fadghost.notesapp

import com.fadghost.notesapp.data.ai.work.TranscribeQueueWorker
import com.fadghost.notesapp.data.audio.RecordedSegment
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TranscribeQueueNamingTest {
    @Test fun sameCaptureHasStableUniqueWorkName() {
        val segments = listOf(RecordedSegment("/capture-a/segment_000.m4a", 1_000L))
        val first = TranscribeQueueWorker.uniqueName(42L, segments)
        val second = TranscribeQueueWorker.uniqueName(42L, segments)
        assertEquals(first, second)
        assertTrue(first.startsWith("voice_transcribe_42_"))
    }

    @Test fun twoOfflineCapturesInOneNoteDoNotReplaceEachOther() {
        val first = listOf(RecordedSegment("/capture-a/segment_000.m4a", 1_000L))
        val second = listOf(RecordedSegment("/capture-b/segment_000.m4a", 1_000L))
        assertNotEquals(
            TranscribeQueueWorker.uniqueName(42L, first),
            TranscribeQueueWorker.uniqueName(42L, second)
        )
    }
}
