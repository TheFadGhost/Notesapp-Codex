package com.fadghost.notesapp.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.ai.AiRepository
import com.fadghost.notesapp.data.ai.Connectivity
import com.fadghost.notesapp.data.ai.net.OpenRouterError
import com.fadghost.notesapp.data.ai.work.TranscribeQueueWorker
import com.fadghost.notesapp.data.audio.AudioAttachmentRepository
import com.fadghost.notesapp.data.audio.RecordedSegment
import com.fadghost.notesapp.data.audio.VoiceCommit
import com.fadghost.notesapp.data.audio.VoiceProgress
import com.fadghost.notesapp.data.audio.VoiceRecordingSession
import com.fadghost.notesapp.data.audio.VoiceSessionState
import com.fadghost.notesapp.data.audio.VoiceTranscriber
import com.fadghost.notesapp.data.db.entity.Note
import com.fadghost.notesapp.data.repo.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.util.UUID
import javax.inject.Inject
import kotlinx.coroutines.Job
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class VoicePhase { REQUEST_PERMISSION, DENIED, STARTING, RECORDING, PROCESSING, QUEUED, DONE, ERROR }

object VoiceStartPolicy {
    fun canStart(phase: VoicePhase): Boolean = phase == VoicePhase.REQUEST_PERMISSION
}

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.REQUEST_PERMISSION,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    val amplitudes: List<Float> = emptyList(),
    val progress: String? = null,
    val transcript: String? = null,
    val segments: List<RecordedSegment> = emptyList(),
    val error: String? = null,
    val committedNoteId: Long? = null
)

/**
 * UI/transcription coordinator. Microphone ownership deliberately lives in
 * [VoiceRecordingSession], allowing the in-app sheet and system overlay to control one recorder.
 */
@HiltViewModel
class VoiceRecordViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notes: NotesRepository,
    private val attachments: AudioAttachmentRepository,
    private val transcriber: VoiceTranscriber,
    private val voiceCommit: VoiceCommit,
    private val connectivity: Connectivity,
    private val aiRepo: AiRepository
) : ViewModel() {
    private val _state = MutableStateFlow(VoiceUiState())
    val state: StateFlow<VoiceUiState> = _state.asStateFlow()

    private var pipelineJob: Job? = null
    private var noteId = 0L
    private var appendMode = false
    private var transcriptOnly = false
    private var createdEmptyNote = false
    private var noteReady = false
    private var wantStart = false
    private var sessionId = UUID.randomUUID().toString()
    private var consumedFinishedSession: String? = null
    private val consumerId = UUID.randomUUID().toString()
    private var claimedSessionId: String? = null
    private var audioCommitted = false

    init {
        viewModelScope.launch {
            VoiceRecordingSession.state.collect { session ->
                if (session.sessionId != sessionId) return@collect
                when (session) {
                    is VoiceSessionState.Starting -> _state.value = _state.value.copy(
                        phase = VoicePhase.STARTING,
                        error = null
                    )
                    is VoiceSessionState.Recording -> _state.value = _state.value.copy(
                        phase = VoicePhase.RECORDING,
                        paused = session.paused,
                        elapsedMs = session.elapsedMs,
                        amplitudes = session.amplitudes
                    )
                    is VoiceSessionState.Finished -> if (consumedFinishedSession != session.sessionId) {
                        VoiceRecordingSession.claimFinished(session.sessionId, consumerId)?.let { claimed ->
                            claimedSessionId = claimed.sessionId
                            consumedFinishedSession = claimed.sessionId
                            onRecordingFinished(claimed.segments)
                        }
                    }
                    is VoiceSessionState.Failed -> handleSessionFailure(session)
                    VoiceSessionState.Idle -> Unit
                }
            }
        }
    }

    /** Prepare a session. [targetNoteId] 0 creates a new note after the user opens voice. */
    fun begin(targetNoteId: Long, append: Boolean, transcriptOnly: Boolean = false) {
        val existing = VoiceRecordingSession.state.value
        val sameTarget = existing.targetNoteId == targetNoteId ||
            (targetNoteId <= 0 && existing.targetNoteId != null && (transcriptOnly || !append))
        if (existing.sessionId != null && existing.transcriptOnly == transcriptOnly && sameTarget &&
            (existing is VoiceSessionState.Starting || existing is VoiceSessionState.Recording || existing is VoiceSessionState.Finished)
        ) {
            appendMode = append
            this.transcriptOnly = transcriptOnly
            sessionId = existing.sessionId!!
            noteId = existing.targetNoteId ?: -1
            noteReady = true
            createdEmptyNote = !transcriptOnly && targetNoteId <= 0
            if (existing is VoiceSessionState.Starting) {
                _state.value = VoiceUiState(phase = VoicePhase.STARTING)
            } else if (existing is VoiceSessionState.Recording) {
                _state.value = VoiceUiState(
                    phase = VoicePhase.RECORDING,
                    paused = existing.paused,
                    elapsedMs = existing.elapsedMs,
                    amplitudes = existing.amplitudes
                )
            } else if (existing is VoiceSessionState.Finished && consumedFinishedSession != existing.sessionId) {
                // Even when another coordinator already owns the terminal claim, this owner is
                // not a fresh permission state and must never start a replacement recorder.
                _state.value = VoiceUiState(phase = VoicePhase.PROCESSING, progress = "Finishing recording…")
                VoiceRecordingSession.claimFinished(existing.sessionId, consumerId)?.let { claimed ->
                    claimedSessionId = claimed.sessionId
                    consumedFinishedSession = claimed.sessionId
                    onRecordingFinished(claimed.segments)
                }
            }
            return
        }
        pipelineJob?.cancel()
        appendMode = append
        this.transcriptOnly = transcriptOnly
        createdEmptyNote = false
        noteReady = false
        wantStart = false
        sessionId = UUID.randomUUID().toString()
        consumedFinishedSession = null
        claimedSessionId = null
        audioCommitted = false
        noteId = 0L
        _state.value = VoiceUiState(phase = VoicePhase.REQUEST_PERMISSION)
        viewModelScope.launch {
            noteId = if (transcriptOnly) -1L else if (targetNoteId > 0) targetNoteId else {
                createdEmptyNote = true
                val now = System.currentTimeMillis()
                notes.saveNote(Note(id = 0, title = "", body = "", createdAt = now, updatedAt = now))
            }
            noteReady = true
            if (wantStart) actuallyStart()
        }
    }

    fun startRecording() {
        if (!VoiceStartPolicy.canStart(_state.value.phase)) return
        wantStart = true
        if (noteReady) actuallyStart()
    }

    private fun actuallyStart() {
        wantStart = false
        val dir = if (transcriptOnly) {
            File(context.cacheDir, "voice_transient/$sessionId")
        } else {
            com.fadghost.notesapp.data.audio.AudioStorage.sessionDir(attachments.noteDir(noteId), sessionId)
        }
        VoiceRecordingSession.start(context, sessionId, dir, noteId, transcriptOnly)
        _state.value = _state.value.copy(phase = VoicePhase.STARTING, error = null)
    }

    fun onPermissionDenied() { _state.value = _state.value.copy(phase = VoicePhase.DENIED) }

    fun togglePause() = VoiceRecordingSession.togglePause(context, sessionId)

    fun stop() = VoiceRecordingSession.stop(context, sessionId)

    private fun onRecordingFinished(segments: List<RecordedSegment>) {
        if (!connectivity.isOnline()) {
            if (transcriptOnly) {
                deleteTransient(segments)
                acknowledgeClaim()
                _state.value = _state.value.copy(
                    phase = VoicePhase.ERROR,
                    error = "Connect to the internet to transcribe this diary recording.",
                    segments = emptyList()
                )
                return
            }
            TranscribeQueueWorker.enqueue(context, noteId, segments, sessionId)
            _state.value = _state.value.copy(phase = VoicePhase.QUEUED, segments = segments)
            acknowledgeClaim()
        } else runTranscription(segments)
    }

    private fun runTranscription(segments: List<RecordedSegment>) {
        _state.value = _state.value.copy(
            phase = VoicePhase.PROCESSING,
            progress = "Uploading…",
            error = null,
            segments = segments
        )
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            try {
                val transcript = transcriber.transcribe(
                    segments.map { File(it.path) },
                    noteId = noteId.takeUnless { transcriptOnly }
                ) { progress ->
                    _state.value = _state.value.copy(progress = label(progress))
                }
                if (appendMode || transcriptOnly) {
                    _state.value = _state.value.copy(phase = VoicePhase.DONE, transcript = transcript, progress = null)
                    if (transcriptOnly) deleteTransient(segments)
                    acknowledgeClaim()
                } else {
                    voiceCommit.appendTranscript(noteId, transcript, segments)
                    audioCommitted = true
                    maybeAutoClean(noteId)
                    _state.value = _state.value.copy(phase = VoicePhase.DONE, committedNoteId = noteId, progress = null)
                    acknowledgeClaim()
                }
            } catch (e: Exception) {
                deleteUncommitted(segments)
                acknowledgeClaim()
                cleanupPlaceholderIfNeeded()
                _state.value = _state.value.copy(
                    phase = VoicePhase.ERROR,
                    error = "${friendly(e)} This recording was not committed; record it again.",
                    segments = emptyList()
                )
            }
        }
    }

    private suspend fun maybeAutoClean(id: Long) {
        if (!transcriber.autoClean() || !connectivity.isOnline()) return
        runCatching {
            val note = notes.getNote(id) ?: return
            if (note.body.isBlank()) return
            val cleaned = aiRepo.cleanupOnce(note.body, id)
            notes.saveNote(note.copy(body = cleaned, updatedAt = System.currentTimeMillis()))
        }
    }

    fun commitEditorAttachment(transcriptStart: Int, transcriptEnd: Int) {
        val segments = _state.value.segments
        if (segments.isEmpty()) return
        viewModelScope.launch {
            attachments.record(noteId, segments, transcriptStart, transcriptEnd)
            audioCommitted = true
        }
    }

    fun retry() {
        _state.value = _state.value.copy(error = "Retry is disabled to avoid charging twice. Please make a new recording.")
    }

    fun discard() {
        pipelineJob?.cancel()
        VoiceRecordingSession.discard(context, sessionId)
        if (transcriptOnly) deleteTransient(_state.value.segments)
        else deleteUncommitted(_state.value.segments)
        acknowledgeClaim()
        VoiceRecordingSession.clearTerminal(sessionId)
        val id = noteId
        val deleteEmpty = createdEmptyNote
        viewModelScope.launch { if (deleteEmpty && id > 0) runCatching { notes.hardDeleteIfEmpty(id) } }
        _state.value = VoiceUiState(phase = VoicePhase.REQUEST_PERMISSION)
    }

    fun cancelProcessing() {
        pipelineJob?.cancel()
        deleteUncommitted(_state.value.segments)
        acknowledgeClaim()
        cleanupPlaceholderIfNeeded()
        _state.value = _state.value.copy(phase = VoicePhase.ERROR, error = "Cancelled. The uncommitted recording was deleted.", segments = emptyList())
    }

    private fun label(progress: VoiceProgress): String = when (progress) {
        is VoiceProgress.Uploading -> if (progress.total > 1) "Uploading ${progress.index}/${progress.total}" else "Uploading…"
        is VoiceProgress.Transcribing -> if (progress.total > 1) "Transcribing ${progress.index}/${progress.total}" else "Transcribing…"
        VoiceProgress.Done -> "Done"
    }

    private fun friendly(error: Throwable): String = when (error) {
        is OpenRouterError.InvalidKey -> "Your API key was rejected. Check it in Settings."
        is OpenRouterError.NoCredit -> "Your OpenRouter account is out of credit."
        is OpenRouterError.RateLimited -> "Rate limited — try again in a moment."
        is OpenRouterError.ModelUnavailable -> "STT model \"${error.model}\" is unavailable. Pick another in Settings."
        is OpenRouterError.Network -> "Couldn't reach OpenRouter. Your audio is saved."
        is OpenRouterError.Parse -> "Couldn't read the transcript. Try again."
        else -> "Something went wrong. Your audio is saved."
    }

    private fun deleteTransient(segments: List<RecordedSegment>) {
        val parents = segments.map { File(it.path).parentFile }.toSet()
        segments.forEach { runCatching { File(it.path).delete() } }
        parents.forEach { parent -> if (parent != null) runCatching {
            com.fadghost.notesapp.data.audio.AudioStorage.pruneEmptySessionParents(parent)
        } }
    }

    private fun deleteUncommitted(segments: List<RecordedSegment>) {
        if (!audioCommitted) deleteTransient(segments)
    }

    private fun acknowledgeClaim() {
        claimedSessionId?.let { VoiceRecordingSession.acknowledge(it, consumerId) }
        claimedSessionId = null
    }

    private fun handleSessionFailure(failure: VoiceSessionState.Failed) {
        deleteUncommitted(_state.value.segments)
        cleanupPlaceholderIfNeeded()
        VoiceRecordingSession.clearTerminal(failure.sessionId)
        _state.value = _state.value.copy(phase = VoicePhase.ERROR, error = failure.message, segments = emptyList())
    }

    private fun cleanupPlaceholderIfNeeded() {
        val id = noteId
        if (createdEmptyNote && id > 0) viewModelScope.launch { runCatching { notes.hardDeleteIfEmpty(id) } }
        if (transcriptOnly) runCatching { File(context.cacheDir, "voice_transient/$sessionId").deleteRecursively() }
    }

    override fun onCleared() {
        pipelineJob?.cancel()
        if (_state.value.phase == VoicePhase.PROCESSING) {
            deleteUncommitted(_state.value.segments)
            acknowledgeClaim()
            cleanupPlaceholderDetached()
        } else {
            claimedSessionId?.let { VoiceRecordingSession.releaseClaim(it, consumerId) }
        }
        // Recording intentionally survives the UI owner; the foreground service remains owner.
        super.onCleared()
    }

    private fun cleanupPlaceholderDetached() {
        val id = noteId
        val shouldDelete = createdEmptyNote && id > 0
        if (!shouldDelete) return
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            runCatching { notes.hardDeleteIfEmpty(id) }
        }
    }
}
