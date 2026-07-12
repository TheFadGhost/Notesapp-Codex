package com.fadghost.notesapp.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.ai.AiRepository
import com.fadghost.notesapp.data.ai.Connectivity
import com.fadghost.notesapp.data.ai.net.OpenRouterError
import com.fadghost.notesapp.data.audio.AudioAttachmentRepository
import com.fadghost.notesapp.data.audio.AudioRecorder
import com.fadghost.notesapp.data.audio.RecordedSegment
import com.fadghost.notesapp.data.audio.VoiceCommit
import com.fadghost.notesapp.data.audio.VoiceProgress
import com.fadghost.notesapp.data.audio.VoiceTranscriber
import com.fadghost.notesapp.data.ai.work.TranscribeQueueWorker
import com.fadghost.notesapp.data.db.entity.Note
import com.fadghost.notesapp.data.repo.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject
import kotlin.math.sqrt

enum class VoicePhase { REQUEST_PERMISSION, DENIED, RECORDING, PROCESSING, QUEUED, DONE, ERROR }

data class VoiceUiState(
    val phase: VoicePhase = VoicePhase.REQUEST_PERMISSION,
    val paused: Boolean = false,
    val elapsedMs: Long = 0,
    val amplitudes: List<Float> = emptyList(),
    /** Human progress line during PROCESSING (e.g. "Uploading 1/2", "Transcribing…"). */
    val progress: String? = null,
    /** Append-mode transcript, handed to the editor to insert at the caret. */
    val transcript: String? = null,
    val segments: List<RecordedSegment> = emptyList(),
    val error: String? = null,
    /** New-note-mode: the note the transcript was committed into (host opens editor). */
    val committedNoteId: Long? = null
)

/**
 * Drives one voice-ramble session (PLAN.md §5): permission gating, recording with
 * live amplitude/timer + auto-segmenting, then the STT pipeline (uploading n/m →
 * transcribing → done) with cancel at every stage and an offline queued state. Two
 * modes: capture-sheet (new note — this VM creates + commits the note) and editor
 * append (returns the transcript for the editor to insert at the caret, then records
 * the attachment). One session at a time; [begin] fully resets.
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

    private var recorder: AudioRecorder? = null
    private var tickJob: Job? = null
    private var pipelineJob: Job? = null

    /**
     * Serialises every [MediaRecorder] lifecycle call (start/stop/pause/resume and the
     * segment rollover) so a rollover from the amplitude ticker can never race the
     * user's stop/discard on the recorder (audit M4). Recorder calls run on
     * [Dispatchers.IO]; only the amplitude/timer sampling stays on the main thread.
     */
    private val recorderLock = Mutex()
    private var noteId = 0L
    private var appendMode = false
    private var createdEmptyNote = false
    private var noteReady = false
    private var wantStart = false

    /** Prepare a session. [noteId] 0 => create a new note (capture sheet). */
    fun begin(noteId: Long, append: Boolean) {
        cancelJobs()
        recorder = null
        appendMode = append
        createdEmptyNote = false
        noteReady = false
        wantStart = false
        this.noteId = 0L
        _state.value = VoiceUiState(phase = VoicePhase.REQUEST_PERMISSION)
        viewModelScope.launch {
            this@VoiceRecordViewModel.noteId = if (noteId > 0) {
                noteId
            } else {
                createdEmptyNote = true
                val now = System.currentTimeMillis()
                notes.saveNote(Note(id = 0, title = "", body = "", createdAt = now, updatedAt = now))
            }
            noteReady = true
            // If the user already granted permission and we asked to start, go now that
            // the target note (and its dir) exists — avoids recording into note id 0.
            if (wantStart) actuallyStart()
        }
    }

    /** Permission granted — begin capturing (defers until the target note is ready). */
    fun startRecording() {
        if (_state.value.phase == VoicePhase.RECORDING) return
        wantStart = true
        if (noteReady) actuallyStart()
    }

    private fun actuallyStart() {
        wantStart = false
        val rec = AudioRecorder(context, attachments.noteDir(resolveNoteId()))
        recorder = rec
        viewModelScope.launch {
            // prepare()/start() are blocking MediaRecorder calls — keep them off Main.
            val started = withContext(Dispatchers.IO) {
                recorderLock.withLock { runCatching { rec.start() }.isSuccess }
            }
            if (!started) {
                _state.value = _state.value.copy(phase = VoicePhase.ERROR, error = "Couldn't start recording.")
                return@launch
            }
            _state.value = _state.value.copy(phase = VoicePhase.RECORDING, paused = false, elapsedMs = 0, amplitudes = emptyList())
            startTicking()
        }
    }

    fun onPermissionDenied() {
        _state.value = _state.value.copy(phase = VoicePhase.DENIED)
    }

    fun togglePause() {
        val rec = recorder ?: return
        viewModelScope.launch {
            val nowPaused = withContext(Dispatchers.IO) {
                recorderLock.withLock {
                    if (rec.isPaused) { rec.resume(); false } else { rec.pause(); true }
                }
            }
            _state.value = _state.value.copy(paused = nowPaused)
        }
    }

    /** Stop capture and run (or queue) transcription. */
    fun stop() {
        val rec = recorder ?: return
        tickJob?.cancel()
        viewModelScope.launch {
            // stop() finalises the current segment (recorder.stop/release) — off Main and
            // under the lock so it can't collide with an in-flight rollover (audit M4).
            val segments = withContext(Dispatchers.IO) {
                recorderLock.withLock { runCatching { rec.stop() }.getOrDefault(emptyList()) }
            }
            if (segments.isEmpty()) {
                _state.value = _state.value.copy(phase = VoicePhase.ERROR, error = "Nothing was recorded.")
                return@launch
            }
            if (!connectivity.isOnline()) {
                TranscribeQueueWorker.enqueue(context, resolveNoteId(), segments)
                _state.value = _state.value.copy(phase = VoicePhase.QUEUED, segments = segments)
                return@launch
            }
            runTranscription(segments)
        }
    }

    private fun runTranscription(segments: List<RecordedSegment>) {
        _state.value = _state.value.copy(phase = VoicePhase.PROCESSING, progress = "Uploading…", error = null, segments = segments)
        pipelineJob?.cancel()
        pipelineJob = viewModelScope.launch {
            try {
                val files = segments.map { File(it.path) }
                val transcript = transcriber.transcribe(files, resolveNoteId()) { p -> _state.value = _state.value.copy(progress = label(p)) }
                if (appendMode) {
                    // Editor inserts at the caret; hand back the transcript + segments.
                    _state.value = _state.value.copy(phase = VoicePhase.DONE, transcript = transcript, progress = null)
                } else {
                    voiceCommit.appendTranscript(resolveNoteId(), transcript, segments)
                    maybeAutoClean(resolveNoteId())
                    _state.value = _state.value.copy(phase = VoicePhase.DONE, committedNoteId = resolveNoteId(), progress = null)
                }
            } catch (e: Exception) {
                _state.value = _state.value.copy(phase = VoicePhase.ERROR, error = friendly(e))
            }
        }
    }

    /** Auto-run the M2 Clean-up flow on the freshly-appended note when enabled + online. */
    private suspend fun maybeAutoClean(id: Long) {
        if (!transcriber.autoClean() || !connectivity.isOnline()) return
        runCatching {
            val note = notes.getNote(id) ?: return
            if (note.body.isBlank()) return
            val cleaned = aiRepo.cleanupOnce(note.body, id)
            notes.saveNote(note.copy(body = cleaned, updatedAt = System.currentTimeMillis()))
        }
    }

    /**
     * Append-mode: after the editor has inserted the transcript at the caret it calls
     * this with the resolved offsets so the audio attachment (chip anchor) is recorded.
     */
    fun commitEditorAttachment(transcriptStart: Int, transcriptEnd: Int) {
        val segments = _state.value.segments
        if (segments.isEmpty()) return
        val id = resolveNoteId()
        viewModelScope.launch {
            attachments.record(id, segments, transcriptStart, transcriptEnd)
        }
    }

    fun retry() {
        val segments = _state.value.segments
        if (segments.isNotEmpty()) runTranscription(segments)
    }

    /** Discard the recording (and the empty note if we created one). */
    fun discard() {
        cancelJobs()
        val rec = recorder
        recorder = null
        val id = noteId
        val hadEmptyNote = createdEmptyNote
        viewModelScope.launch {
            withContext(Dispatchers.IO) { recorderLock.withLock { runCatching { rec?.discard() } } }
            if (hadEmptyNote && id > 0) runCatching { notes.hardDeleteIfEmpty(id) }
        }
        _state.value = VoiceUiState(phase = VoicePhase.REQUEST_PERMISSION)
    }

    /** Cancel an in-flight upload/transcription (returns to a discardable state). */
    fun cancelProcessing() {
        pipelineJob?.cancel()
        _state.value = _state.value.copy(phase = VoicePhase.ERROR, error = "Cancelled.")
    }

    private fun startTicking() {
        tickJob?.cancel()
        tickJob = viewModelScope.launch {
            while (true) {
                val rec = recorder ?: break
                if (!rec.isPaused) {
                    // Rollover finalises/starts a segment — run it off Main under the lock
                    // (audit M4). The amplitude/timer sampling below stays on Main.
                    withContext(Dispatchers.IO) { recorderLock.withLock { rec.maybeRollover() } }
                    val amp = normalize(rec.amplitude())
                    val next = (_state.value.amplitudes + amp).takeLast(MAX_SAMPLES)
                    _state.value = _state.value.copy(elapsedMs = rec.totalElapsedMs(), amplitudes = next)
                }
                delay(SAMPLE_INTERVAL_MS)
            }
        }
    }

    private fun normalize(raw: Int): Float {
        val n = (raw.toFloat() / MAX_AMPLITUDE).coerceIn(0f, 1f)
        return sqrt(n) // perceptual boost for quiet speech
    }

    private fun label(p: VoiceProgress): String = when (p) {
        is VoiceProgress.Uploading -> if (p.total > 1) "Uploading ${p.index}/${p.total}" else "Uploading…"
        is VoiceProgress.Transcribing -> if (p.total > 1) "Transcribing ${p.index}/${p.total}" else "Transcribing…"
        VoiceProgress.Done -> "Done"
    }

    private fun friendly(e: Throwable): String = when (e) {
        is OpenRouterError.InvalidKey -> "Your API key was rejected. Check it in Settings."
        is OpenRouterError.NoCredit -> "Your OpenRouter account is out of credit."
        is OpenRouterError.RateLimited -> "Rate limited — try again in a moment."
        is OpenRouterError.ModelUnavailable -> "That STT model is unavailable. Pick another in Settings."
        is OpenRouterError.Network -> "Couldn't reach OpenRouter. Your audio is saved."
        is OpenRouterError.Parse -> "Couldn't read the transcript. Try again."
        else -> "Something went wrong. Your audio is saved."
    }

    private fun resolveNoteId(): Long = noteId

    private fun cancelJobs() {
        tickJob?.cancel(); tickJob = null
        pipelineJob?.cancel(); pipelineJob = null
    }

    override fun onCleared() {
        super.onCleared()
        cancelJobs()
        val rec = recorder
        recorder = null
        // viewModelScope is cancelled by now; best-effort stop off Main on a detached scope.
        CoroutineScope(Dispatchers.IO).launch {
            recorderLock.withLock { runCatching { rec?.stop() } }
        }
    }

    private companion object {
        const val SAMPLE_INTERVAL_MS = 80L
        const val MAX_SAMPLES = 96
        const val MAX_AMPLITUDE = 32767f
    }
}
