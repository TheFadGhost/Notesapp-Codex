package com.fadghost.notesapp.ui.diary

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.ai.Connectivity
import com.fadghost.notesapp.data.ai.net.OpenRouterError
import com.fadghost.notesapp.data.audio.RecordingServiceClient
import com.fadghost.notesapp.data.audio.VoiceDestination
import com.fadghost.notesapp.data.audio.VoiceProgress
import com.fadghost.notesapp.data.audio.VoiceRuntimeState
import com.fadghost.notesapp.data.audio.VoiceSession
import com.fadghost.notesapp.data.audio.VoiceSessionPhase
import com.fadghost.notesapp.data.audio.VoiceSessionStore
import com.fadghost.notesapp.data.audio.VoiceTranscriber
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.io.File
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

enum class DiaryVoiceStage {
    IDLE,
    PREPARING,
    RECORDING,
    PAUSED,
    PROCESSING,
    OFFLINE,
    READY,
    ERROR
}

data class DiaryVoiceUiState(
    val sessionId: String? = null,
    val date: LocalDate? = null,
    val stage: DiaryVoiceStage = DiaryVoiceStage.IDLE,
    val elapsedMs: Long = 0L,
    val amplitudes: List<Float> = emptyList(),
    val progress: String? = null,
    val transcript: String? = null,
    val error: String? = null,
    val hasSavedAudio: Boolean = false
) {
    val capturing: Boolean get() = stage == DiaryVoiceStage.RECORDING || stage == DiaryVoiceStage.PAUSED
    val paused: Boolean get() = stage == DiaryVoiceStage.PAUSED
}

private data class DiaryVoiceProjection(
    val sessions: List<VoiceSession>,
    val runtime: VoiceRuntimeState?,
    val selectedId: String?,
    val working: Boolean,
    val progress: String?
)

internal const val DIARY_VOICE_OFFLINE = "offline"

internal fun diaryVoiceStageFor(
    phase: VoiceSessionPhase,
    livePhase: VoiceSessionPhase?,
    errorCode: String?,
    working: Boolean
): DiaryVoiceStage = when {
    working || phase == VoiceSessionPhase.TRANSCRIBING -> DiaryVoiceStage.PROCESSING
    errorCode == DIARY_VOICE_OFFLINE -> DiaryVoiceStage.OFFLINE
    livePhase == VoiceSessionPhase.RECORDING || phase == VoiceSessionPhase.RECORDING ->
        DiaryVoiceStage.RECORDING
    livePhase == VoiceSessionPhase.PAUSED || phase == VoiceSessionPhase.PAUSED ->
        DiaryVoiceStage.PAUSED
    phase == VoiceSessionPhase.PREPARING -> DiaryVoiceStage.PREPARING
    phase == VoiceSessionPhase.RECORDED -> DiaryVoiceStage.PROCESSING
    phase == VoiceSessionPhase.TRANSCRIPT_READY -> DiaryVoiceStage.READY
    phase in setOf(VoiceSessionPhase.COMPLETE, VoiceSessionPhase.DISCARDED) -> DiaryVoiceStage.IDLE
    else -> DiaryVoiceStage.ERROR
}

internal fun diaryDiscardNeedsService(phase: VoiceSessionPhase, hasSegments: Boolean): Boolean =
    phase == VoiceSessionPhase.PREPARING || phase.isCapturing ||
        (phase == VoiceSessionPhase.ERROR && !hasSegments)

/**
 * Transcript-only diary capture. No Note row or audio attachment is ever created: audio lives
 * under the voice-session directory until the transcript is inserted or explicitly discarded.
 */
@HiltViewModel
class DiaryVoiceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val sessions: VoiceSessionStore,
    private val transcriber: VoiceTranscriber,
    private val connectivity: Connectivity
) : ViewModel() {

    private val selectedId = MutableStateFlow<String?>(null)
    private val working = MutableStateFlow(false)
    private val progress = MutableStateFlow<String?>(null)
    private val localError = MutableStateFlow<String?>(null)
    private var transcribeJob: Job? = null
    private var discardPendingId: String? = null

    private val projection = combine(
        sessions.sessions,
        sessions.runtime,
        selectedId,
        working,
        progress
    ) { all, runtime, selected, isWorking, progressText ->
        DiaryVoiceProjection(all, runtime, selected, isWorking, progressText)
    }

    val state: StateFlow<DiaryVoiceUiState> = combine(projection, localError) { input, local ->
        val session = input.selectedId?.let { id -> input.sessions.firstOrNull { it.id == id } }
        val live = input.runtime?.takeIf { it.sessionId == session?.id }
        if (session == null) {
            DiaryVoiceUiState(error = local)
        } else {
            val date = session.diaryDate?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
            val stage = diaryVoiceStageFor(session.phase, live?.phase, session.errorCode, input.working)
            DiaryVoiceUiState(
                sessionId = session.id,
                date = date,
                stage = stage,
                elapsedMs = live?.elapsedMs ?: session.segments.sumOf { it.durationMs },
                amplitudes = live?.amplitudes.orEmpty(),
                progress = input.progress,
                transcript = session.transcript,
                error = local ?: live?.error ?: session.errorMessage,
                hasSavedAudio = session.segments.isNotEmpty()
            )
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryVoiceUiState())

    init {
        // RecordingService deliberately leaves diary sessions at RECORDED. This observer owns
        // the transcript-only continuation and cleanup, including when the sheet is hidden.
        viewModelScope.launch {
            combine(selectedId, sessions.sessions) { id, all ->
                id?.let { selected -> all.firstOrNull { it.id == selected } }
            }.collect { session ->
                when {
                    session == null -> Unit
                    session.phase == VoiceSessionPhase.DISCARDED -> cleanup(session.id)
                    session.phase == VoiceSessionPhase.RECORDED &&
                        session.errorCode == null && transcribeJob?.isActive != true -> beginTranscription(session)
                    session.phase == VoiceSessionPhase.TRANSCRIBING &&
                        transcribeJob?.isActive != true -> {
                        // A process died mid-upload. The audio is durable, so make retry explicit.
                        sessions.update(session.id) {
                            it.copy(
                                phase = VoiceSessionPhase.RECORDED,
                                errorCode = DIARY_VOICE_OFFLINE,
                                errorMessage = "The transcript was interrupted. Tap Retry to continue."
                            )
                        }
                    }
                }
            }
        }
    }

    /** Select a recoverable session for [date] when its editor opens. Does not start recording. */
    fun attach(date: LocalDate) {
        val sameDate = sessions.sessions.value.firstOrNull {
            it.destination == VoiceDestination.DIARY_TRANSCRIPT && it.diaryDate == date.toString() &&
                it.phase !in setOf(VoiceSessionPhase.COMPLETE, VoiceSessionPhase.DISCARDED)
        }
        val otherActive = sessions.sessions.value.firstOrNull {
            it.destination == VoiceDestination.DIARY_TRANSCRIPT &&
                it.phase !in setOf(VoiceSessionPhase.COMPLETE, VoiceSessionPhase.DISCARDED)
        }
        selectedId.value = sameDate?.id ?: otherActive?.id
        localError.value = if (sameDate == null && otherActive != null) {
            "Finish or discard the recording for ${otherActive.diaryDate} first."
        } else null
    }

    /** Called only after the visible Activity has granted RECORD_AUDIO. */
    fun start(date: LocalDate, now: Long = System.currentTimeMillis()) {
        viewModelScope.launch {
            val existing = currentSession()
            if (existing != null && existing.diaryDate == date.toString()) {
                when (existing.phase) {
                    VoiceSessionPhase.INTERRUPTED -> RecordingServiceClient.start(context, existing.id)
                    VoiceSessionPhase.RECORDED, VoiceSessionPhase.ERROR -> retry()
                    else -> Unit
                }
                return@launch
            }
            val microphoneOwner = sessions.sessions.value.firstOrNull {
                it.phase == VoiceSessionPhase.PREPARING || it.phase.isCapturing
            }
            if (microphoneOwner != null) {
                localError.value = "Another voice recording is already active."
                return@launch
            }
            val session = VoiceSession(
                id = UUID.randomUUID().toString(),
                destination = VoiceDestination.DIARY_TRANSCRIPT,
                diaryDate = date.toString(),
                capturedAt = now,
                zoneId = ZoneId.systemDefault().id
            )
            runCatching {
                sessions.put(session)
                selectedId.value = session.id
                localError.value = null
                RecordingServiceClient.start(context, session.id)
            }.onFailure {
                sessions.remove(session.id)
                selectedId.value = null
                localError.value = "Couldn't start recording."
            }
        }
    }

    fun pause() {
        selectedId.value?.let { RecordingServiceClient.pause(context, it) }
    }

    fun resume() {
        selectedId.value?.let { RecordingServiceClient.resume(context, it) }
    }

    fun stop() {
        selectedId.value?.let { RecordingServiceClient.stop(context, it) }
    }

    fun retry() {
        viewModelScope.launch {
            val session = currentSession() ?: return@launch
            localError.value = null
            when {
                session.phase == VoiceSessionPhase.INTERRUPTED && session.segments.isEmpty() ->
                    RecordingServiceClient.start(context, session.id)
                session.segments.isEmpty() ->
                    localError.value = "There is no saved audio to retry."
                !connectivity.isOnline() -> sessions.update(session.id) {
                    it.copy(
                        phase = VoiceSessionPhase.RECORDED,
                        errorCode = DIARY_VOICE_OFFLINE,
                        errorMessage = "You're offline. The audio is saved; retry when you're connected."
                    )
                }
                else -> {
                    sessions.update(session.id) {
                        it.copy(phase = VoiceSessionPhase.RECORDED, errorCode = null, errorMessage = null)
                    }?.let(::beginTranscription)
                }
            }
        }
    }

    /** Stop active capture safely, or immediately remove already-finalised transient audio. */
    fun discard() {
        viewModelScope.launch {
            val session = currentSession() ?: return@launch
            transcribeJob?.cancelAndJoin()
            transcribeJob = null
            working.value = false
            progress.value = null
            if (diaryDiscardNeedsService(session.phase, session.segments.isNotEmpty())) {
                discardPendingId = session.id
                RecordingServiceClient.discard(context, session.id)
            } else {
                cleanup(session.id)
            }
        }
    }

    /** Call only after the host synchronously inserted [state.transcript] into the target field. */
    fun acknowledgeInserted() {
        val sessionId = selectedId.value ?: return
        if (state.value.stage != DiaryVoiceStage.READY) return
        viewModelScope.launch { cleanup(sessionId) }
    }

    fun onPermissionDenied() {
        localError.value = "Microphone permission is off."
    }

    private fun beginTranscription(session: VoiceSession) {
        if (transcribeJob?.isActive == true) return
        transcribeJob = viewModelScope.launch {
            if (!connectivity.isOnline()) {
                sessions.update(session.id) {
                    it.copy(
                        phase = VoiceSessionPhase.RECORDED,
                        errorCode = DIARY_VOICE_OFFLINE,
                        errorMessage = "You're offline. The audio is saved; retry when you're connected."
                    )
                }
                return@launch
            }
            val files = session.segments.map { File(it.path) }
            if (files.isEmpty() || files.any { !it.isFile || it.length() <= 0L }) {
                sessions.update(session.id) {
                    it.copy(
                        phase = VoiceSessionPhase.ERROR,
                        errorCode = "missing_audio",
                        errorMessage = "The saved recording is missing."
                    )
                }
                return@launch
            }
            working.value = true
            progress.value = "Preparing audio…"
            sessions.update(session.id) {
                it.copy(phase = VoiceSessionPhase.TRANSCRIBING, errorCode = null, errorMessage = null)
            }
            try {
                val transcript = transcriber.transcribe(files, noteId = null) { update ->
                    progress.value = progressLabel(update)
                }.trim()
                if (transcript.isBlank()) {
                    sessions.update(session.id) {
                        it.copy(
                            phase = VoiceSessionPhase.ERROR,
                            errorCode = "empty_transcript",
                            errorMessage = "No readable speech was found."
                        )
                    }
                } else {
                    sessions.update(session.id) {
                        it.copy(
                            phase = VoiceSessionPhase.TRANSCRIPT_READY,
                            transcript = transcript,
                            errorCode = null,
                            errorMessage = null
                        )
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (t: Throwable) {
                val offline = !connectivity.isOnline() || t is OpenRouterError.Network
                sessions.update(session.id) {
                    it.copy(
                        phase = if (offline) VoiceSessionPhase.RECORDED else VoiceSessionPhase.ERROR,
                        errorCode = if (offline) DIARY_VOICE_OFFLINE else errorCode(t),
                        errorMessage = if (offline) {
                            "Connection lost. The audio is saved; tap Retry when you're online."
                        } else friendly(t)
                    )
                }
            } finally {
                working.value = false
                progress.value = null
            }
        }
    }

    private suspend fun cleanup(sessionId: String) {
        // VoiceSessionStore.remove deletes both AtomicFile metadata and transient audio directory.
        sessions.remove(sessionId)
        if (selectedId.value == sessionId) selectedId.value = null
        if (discardPendingId == sessionId) discardPendingId = null
        localError.value = null
        progress.value = null
    }

    private suspend fun currentSession(): VoiceSession? = selectedId.value?.let { sessions.get(it) }

    private fun progressLabel(progress: VoiceProgress): String = when (progress) {
        is VoiceProgress.Uploading ->
            if (progress.total > 1) "Uploading ${progress.index}/${progress.total}" else "Uploading audio…"
        is VoiceProgress.Transcribing ->
            if (progress.total > 1) "Transcribing ${progress.index}/${progress.total}" else "Transcribing…"
        VoiceProgress.Done -> "Transcript ready"
    }

    private fun errorCode(t: Throwable): String = when (t) {
        is OpenRouterError.InvalidKey -> "invalid_key"
        is OpenRouterError.NoCredit -> "no_credit"
        is OpenRouterError.RateLimited -> "rate_limited"
        is OpenRouterError.ModelUnavailable -> "model_unavailable"
        is OpenRouterError.Parse -> "parse"
        is OpenRouterError.BudgetReached -> "budget_reached"
        else -> "transcription"
    }

    private fun friendly(t: Throwable): String = when (t) {
        is OpenRouterError.InvalidKey -> "Check your OpenRouter key in Settings."
        is OpenRouterError.NoCredit -> "Your OpenRouter account is out of credit."
        is OpenRouterError.RateLimited -> "Rate limited. Your audio is saved; try again shortly."
        is OpenRouterError.ModelUnavailable -> "The selected speech model is unavailable."
        is OpenRouterError.Parse -> "The transcription response couldn't be read."
        is OpenRouterError.BudgetReached -> "Monthly AI budget reached — raise it in Settings → AI. Your audio is saved."
        else -> "Couldn't transcribe this recording. The audio is still saved."
    }
}
