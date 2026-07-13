package com.fadghost.notesapp

import com.fadghost.notesapp.data.audio.VoiceSessionPhase
import com.fadghost.notesapp.ui.diary.DIARY_VOICE_OFFLINE
import com.fadghost.notesapp.ui.diary.DiaryVoiceStage
import com.fadghost.notesapp.ui.diary.diaryDiscardNeedsService
import com.fadghost.notesapp.ui.diary.diaryVoiceStageFor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiaryVoiceLogicTest {
    @Test fun liveRecorderStateWinsPersistedPreparingState() {
        assertEquals(
            DiaryVoiceStage.RECORDING,
            diaryVoiceStageFor(
                phase = VoiceSessionPhase.PREPARING,
                livePhase = VoiceSessionPhase.RECORDING,
                errorCode = null,
                working = false
            )
        )
    }

    @Test fun offlineIsExplicitWhileAudioRemainsRecorded() {
        assertEquals(
            DiaryVoiceStage.OFFLINE,
            diaryVoiceStageFor(
                phase = VoiceSessionPhase.RECORDED,
                livePhase = null,
                errorCode = DIARY_VOICE_OFFLINE,
                working = false
            )
        )
    }

    @Test fun staleTranscribingSessionSurfacesProcessingUntilRecoveryRuns() {
        assertEquals(
            DiaryVoiceStage.PROCESSING,
            diaryVoiceStageFor(
                phase = VoiceSessionPhase.TRANSCRIBING,
                livePhase = null,
                errorCode = null,
                working = false
            )
        )
    }

    @Test fun readyAndTerminalStatesAreDistinct() {
        assertEquals(
            DiaryVoiceStage.READY,
            diaryVoiceStageFor(VoiceSessionPhase.TRANSCRIPT_READY, null, null, false)
        )
        assertEquals(
            DiaryVoiceStage.IDLE,
            diaryVoiceStageFor(VoiceSessionPhase.COMPLETE, null, null, false)
        )
    }

    @Test fun activeOrUnusableFailedCaptureMustDiscardThroughService() {
        assertTrue(diaryDiscardNeedsService(VoiceSessionPhase.PREPARING, hasSegments = false))
        assertTrue(diaryDiscardNeedsService(VoiceSessionPhase.RECORDING, hasSegments = false))
        assertTrue(diaryDiscardNeedsService(VoiceSessionPhase.PAUSED, hasSegments = false))
        assertTrue(diaryDiscardNeedsService(VoiceSessionPhase.ERROR, hasSegments = false))
    }

    @Test fun finalisedAudioCanBeDeletedDirectlyWithoutRestartingService() {
        assertFalse(diaryDiscardNeedsService(VoiceSessionPhase.RECORDED, hasSegments = true))
        assertFalse(diaryDiscardNeedsService(VoiceSessionPhase.TRANSCRIPT_READY, hasSegments = true))
        assertFalse(diaryDiscardNeedsService(VoiceSessionPhase.ERROR, hasSegments = true))
    }
}
