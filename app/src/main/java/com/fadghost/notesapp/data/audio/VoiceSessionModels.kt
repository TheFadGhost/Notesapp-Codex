package com.fadghost.notesapp.data.audio

import kotlinx.serialization.Serializable

/** What should happen after one microphone capture has been finalised. */
@Serializable
enum class VoiceDestination {
    /** Transcribe, organise into a new Note, then offer calendar/reminder confirmation cards. */
    RAMBLE_NOTE,

    /** Append a verbatim transcript to an existing Note and keep the audio attachment. */
    NOTE_APPEND,

    /** Return transcript text to a Diary editor. Audio is transient and is never a Note attachment. */
    DIARY_TRANSCRIPT
}

/** Durable lifecycle. High-frequency waveform samples deliberately stay out of this model. */
@Serializable
enum class VoiceSessionPhase {
    PREPARING,
    RECORDING,
    PAUSED,
    RECORDED,
    TRANSCRIBING,
    ORGANIZING,
    AWAITING_CONFIRMATION,
    TRANSCRIPT_READY,
    COMPLETE,
    ERROR,
    INTERRUPTED,
    DISCARDED;

    val isCapturing: Boolean get() = this == RECORDING || this == PAUSED
    val isTerminal: Boolean get() = this == COMPLETE || this == DISCARDED
}

/** Serializable form of an extracted action. It avoids coupling persistence to UI card classes. */
@Serializable
data class PendingVoiceAction(
    val id: Long,
    val type: String,
    val title: String,
    val datetimeMillis: Long? = null,
    val notes: String? = null
)

/**
 * Crash-resilient record of one capture and its pipeline outputs. Written with [android.util.AtomicFile]
 * after every expensive or externally visible transition so WorkManager retries can resume instead of
 * recreating a note or repeating already-finished stages.
 */
@Serializable
data class VoiceSession(
    val id: String,
    val destination: VoiceDestination,
    val targetNoteId: Long? = null,
    /** ISO yyyy-MM-dd for Diary capture; null for Note destinations. */
    val diaryDate: String? = null,
    /** Wall-clock instant and zone captured when the user began speaking. */
    val capturedAt: Long,
    val zoneId: String,
    val phase: VoiceSessionPhase = VoiceSessionPhase.PREPARING,
    val segments: List<RecordedSegment> = emptyList(),
    val transcript: String? = null,
    val rewrittenTitle: String? = null,
    val rewrittenBody: String? = null,
    val pendingActions: List<PendingVoiceAction> = emptyList(),
    val warnings: List<String> = emptyList(),
    /** Persisted so dismissing confirmation survives Activity recreation without losing cards. */
    val reviewDismissed: Boolean = false,
    /** Raw/friendly action extraction failure; the note itself can still be successfully saved. */
    val actionError: String? = null,
    val errorCode: String? = null,
    val errorMessage: String? = null,
    val noteCommitted: Boolean = false,
    val audioCommitted: Boolean = false,
    val actionsExtracted: Boolean = false,
    val acceptedCount: Int = 0,
    val updatedAt: Long = capturedAt
) {
    init {
        require(id.matches(SAFE_ID)) { "Unsafe voice session id" }
        require(
            when (destination) {
                VoiceDestination.RAMBLE_NOTE, VoiceDestination.NOTE_APPEND ->
                    targetNoteId != null && targetNoteId > 0
                VoiceDestination.DIARY_TRANSCRIPT -> !diaryDate.isNullOrBlank()
            }
        ) { "Voice destination is missing its target" }
    }

    companion object {
        val SAFE_ID = Regex("[A-Za-z0-9_-]{1,80}")
    }
}

/** In-memory live state sampled by the service; never written at waveform frequency. */
data class VoiceRuntimeState(
    val sessionId: String,
    val phase: VoiceSessionPhase,
    val paused: Boolean,
    val elapsedMs: Long,
    val amplitudes: List<Float> = emptyList(),
    val error: String? = null
)
