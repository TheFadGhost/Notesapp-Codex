package com.fadghost.notesapp

import com.fadghost.notesapp.data.ai.work.TranscribeQueueWorker
import com.fadghost.notesapp.data.audio.RecordedSegment
import com.fadghost.notesapp.data.audio.VoiceRecordingSession
import com.fadghost.notesapp.data.audio.VoiceSessionState
import com.fadghost.notesapp.data.audio.VoiceTerminalPolicy
import com.fadghost.notesapp.ui.voice.VoicePhase
import com.fadghost.notesapp.ui.voice.VoiceStartPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceSessionStateTest {
    @Test fun onlyFreshPermissionStateMayStartRecorder() {
        assertTrue(VoiceStartPolicy.canStart(VoicePhase.REQUEST_PERMISSION))
        assertTrue(!VoiceStartPolicy.canStart(VoicePhase.STARTING))
        assertTrue(!VoiceStartPolicy.canStart(VoicePhase.RECORDING))
        assertTrue(!VoiceStartPolicy.canStart(VoicePhase.PROCESSING))
        assertTrue(!VoiceStartPolicy.canStart(VoicePhase.DONE))
    }
    @Test fun discardWinsStartupCommandRace() {
        assertTrue(VoiceTerminalPolicy.merge(existingDiscard = false, incomingDiscard = true))
        assertTrue(VoiceTerminalPolicy.merge(existingDiscard = true, incomingDiscard = false))
    }

    @Test fun finishedCanBeClaimedByExactlyOneCoordinatorAndAcknowledged() {
        val finished = VoiceSessionState.Finished("s1", 4, false, listOf(RecordedSegment("p", 1)))
        VoiceRecordingSession.publish(finished)
        assertNotNull(VoiceRecordingSession.claimFinished("s1", "owner-a"))
        assertNull(VoiceRecordingSession.claimFinished("s1", "owner-b"))
        VoiceRecordingSession.acknowledge("s1", "owner-a")
        assertEquals(VoiceSessionState.Idle, VoiceRecordingSession.state.value)
    }

    @Test fun offlineWorkIdentityIsPerSessionNotPerNote() {
        assertTrue(TranscribeQueueWorker.uniqueName("session-a") != TranscribeQueueWorker.uniqueName("session-b"))
    }
}
