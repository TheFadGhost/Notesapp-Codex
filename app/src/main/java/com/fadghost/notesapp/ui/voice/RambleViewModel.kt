package com.fadghost.notesapp.ui.voice

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.ai.AiRepository
import com.fadghost.notesapp.data.ai.parse.ActionType
import com.fadghost.notesapp.data.ai.parse.ProposedAction
import com.fadghost.notesapp.data.ai.parse.RambleActionNormalizer
import com.fadghost.notesapp.data.ai.work.RamblePipelineWorker
import com.fadghost.notesapp.data.audio.PendingVoiceAction
import com.fadghost.notesapp.data.audio.RecordingServiceClient
import com.fadghost.notesapp.data.audio.VoiceDestination
import com.fadghost.notesapp.data.audio.VoiceRuntimeState
import com.fadghost.notesapp.data.audio.VoiceSession
import com.fadghost.notesapp.data.audio.VoiceSessionPhase
import com.fadghost.notesapp.data.audio.VoiceSessionStore
import com.fadghost.notesapp.data.db.entity.Note
import com.fadghost.notesapp.data.repo.NotesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.ZoneId
import java.util.UUID
import javax.inject.Inject

data class RambleCard(
    val id: Long,
    val action: ProposedAction,
    val busy: Boolean = false
)

/** UI projection of the durable session journal. A hidden review can always be reopened. */
data class RambleUiState(
    val sessionId: String? = null,
    val noteId: Long? = null,
    val phase: VoiceSessionPhase? = null,
    val paused: Boolean = false,
    val elapsedMs: Long = 0L,
    val amplitudes: List<Float> = emptyList(),
    val cards: List<RambleCard> = emptyList(),
    val warnings: List<String> = emptyList(),
    val reviewVisible: Boolean = false,
    val acceptedCount: Int = 0,
    val error: String? = null
) {
    val recording: Boolean get() = phase?.isCapturing == true
    val captureActive: Boolean get() = phase == VoiceSessionPhase.PREPARING || recording
    val processing: Boolean get() = phase in setOf(
        VoiceSessionPhase.RECORDED,
        VoiceSessionPhase.TRANSCRIBING,
        VoiceSessionPhase.ORGANIZING
    )
    val hasPendingReview: Boolean get() = cards.isNotEmpty() || phase == VoiceSessionPhase.AWAITING_CONFIRMATION
}

private data class RambleProjection(
    val sessions: List<VoiceSession>,
    val runtime: VoiceRuntimeState?,
    val selectedId: String?,
    val reviewVisible: Boolean
)

/**
 * Activity-safe controller for feature A. MediaRecorder never lives here: this class creates the
 * placeholder note + durable manifest, commands [com.fadghost.notesapp.service.RecordingService],
 * and persists every confirmation-card mutation before reflecting it in the UI.
 *
 * Call [startRamble] only after RECORD_AUDIO has been granted while the Activity is visible. That
 * keeps Android 12+ foreground-service launch restrictions explicit at the screen boundary.
 */
@HiltViewModel
class RambleViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val notes: NotesRepository,
    private val sessions: VoiceSessionStore,
    private val ai: AiRepository
) : ViewModel() {

    private val selectedId = MutableStateFlow<String?>(null)
    private val reviewVisible = MutableStateFlow(
        sessions.sessions.value.any {
            it.destination == VoiceDestination.RAMBLE_NOTE &&
                it.phase == VoiceSessionPhase.AWAITING_CONFIRMATION && !it.reviewDismissed
        }
    )
    private val busyCards = MutableStateFlow<Set<Long>>(emptySet())
    private val operationError = MutableStateFlow<String?>(null)
    private val mutation = Mutex()

    private val projection = combine(
        sessions.sessions,
        sessions.runtime,
        selectedId,
        reviewVisible
    ) { all, runtime, selected, showReview ->
        RambleProjection(all, runtime, selected, showReview)
    }

    val state: StateFlow<RambleUiState> = combine(
        projection,
        busyCards,
        operationError
    ) { input, busy, localError ->
        val session = input.selectedId?.let { id -> input.sessions.firstOrNull { it.id == id } }
            ?: input.sessions.firstOrNull {
                input.reviewVisible && it.destination == VoiceDestination.RAMBLE_NOTE &&
                    it.phase == VoiceSessionPhase.AWAITING_CONFIRMATION && !it.reviewDismissed
            }
            ?: input.sessions.firstOrNull(::isResumableRamble)
        val live = input.runtime?.takeIf { it.sessionId == session?.id }
        session?.let {
            RambleUiState(
                sessionId = it.id,
                noteId = it.targetNoteId,
                phase = live?.phase ?: it.phase,
                paused = live?.paused ?: (it.phase == VoiceSessionPhase.PAUSED),
                elapsedMs = live?.elapsedMs ?: it.segments.sumOf { segment -> segment.durationMs },
                amplitudes = live?.amplitudes.orEmpty(),
                cards = it.pendingActions.mapNotNull { action -> action.toCard() }
                    .map { card -> card.copy(busy = card.id in busy) },
                warnings = it.warnings,
                reviewVisible = input.reviewVisible && !it.reviewDismissed &&
                    it.phase == VoiceSessionPhase.AWAITING_CONFIRMATION,
                acceptedCount = it.acceptedCount,
                error = localError ?: live?.error ?: it.errorMessage ?: it.actionError
            )
        } ?: RambleUiState(error = localError)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RambleUiState())

    /** Creates the target first, journals it, then launches the microphone FGS. */
    fun startRamble(now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()) {
        viewModelScope.launch {
            mutation.withLock {
                if (sessions.sessions.value.any {
                        it.destination == VoiceDestination.RAMBLE_NOTE &&
                            (it.phase == VoiceSessionPhase.PREPARING || it.phase.isCapturing)
                    }
                ) {
                    operationError.value = "A voice ramble is already recording."
                    return@withLock
                }
                operationError.value = null
                val noteId = runCatching {
                    notes.saveNote(Note(title = "", body = "", createdAt = now, updatedAt = now))
                }.getOrElse {
                    operationError.value = "Couldn't prepare a voice note."
                    return@withLock
                }
                val session = VoiceSession(
                    id = UUID.randomUUID().toString(),
                    destination = VoiceDestination.RAMBLE_NOTE,
                    targetNoteId = noteId,
                    capturedAt = now,
                    zoneId = zone.id
                )
                try {
                    sessions.put(session)
                    selectedId.value = session.id
                    reviewVisible.value = true
                    RecordingServiceClient.start(context, session.id)
                } catch (t: Throwable) {
                    runCatching { notes.hardDeleteIfEmpty(noteId) }
                    sessions.remove(session.id)
                    selectedId.value = null
                    operationError.value = "Couldn't start recording."
                }
            }
        }
    }

    fun pause() = selectedCommand(RecordingServiceClient::pause)
    fun resume() = selectedCommand(RecordingServiceClient::resume)
    fun stop() = selectedCommand(RecordingServiceClient::stop)

    fun discard() {
        val id = selectedId.value ?: sessions.sessions.value.firstOrNull(::isResumableRamble)?.id
        id?.let { RecordingServiceClient.discard(context, it) }
        reviewVisible.value = false
    }

    fun showReview(sessionId: String? = null) {
        val candidate = sessionId ?: sessions.sessions.value.firstOrNull {
            it.destination == VoiceDestination.RAMBLE_NOTE && it.phase == VoiceSessionPhase.AWAITING_CONFIRMATION
        }?.id
        if (candidate != null) selectedId.value = candidate
        operationError.value = null
        reviewVisible.value = true
        if (candidate != null) {
            viewModelScope.launch {
                mutation.withLock { sessions.update(candidate) { it.copy(reviewDismissed = false) } }
            }
        }
    }

    /** Hiding is non-destructive: pending cards remain in the AtomicFile manifest. */
    fun hideReview() {
        reviewVisible.value = false
        operationError.value = null
        viewModelScope.launch {
            mutation.withLock {
                selectedSession()?.let { session ->
                    sessions.update(session.id) { it.copy(reviewDismissed = true) }
                }
            }
        }
    }

    fun rejectCard(cardId: Long) = mutateSession { session ->
        session.withCards(session.pendingActions.filterNot { it.id == cardId })
    }

    fun editCard(cardId: Long, title: String, datetimeMillis: Long?, notesText: String? = null) =
        mutateSession { session ->
            session.withCards(session.pendingActions.map { card ->
                if (card.id != cardId) card else card.copy(
                    title = title.trim().ifBlank { card.title }.take(200),
                    datetimeMillis = datetimeMillis,
                    notes = if (notesText == null) card.notes
                    else notesText.trim().takeIf(String::isNotBlank)?.take(2_000)
                )
            })
        }

    fun reviseCard(cardId: Long, instruction: String) {
        if (instruction.isBlank()) return
        viewModelScope.launch {
            mutation.withLock {
                val session = selectedSession() ?: return@withLock
                val persisted = session.pendingActions.firstOrNull { it.id == cardId } ?: return@withLock
                val current = persisted.toAction() ?: return@withLock
                busyCards.value += cardId
                operationError.value = null
                try {
                    val zone = runCatching { ZoneId.of(session.zoneId) }.getOrDefault(ZoneId.systemDefault())
                    val revised = ai.reviseRambleAction(
                        current,
                        instruction,
                        System.currentTimeMillis(),
                        zone
                    ) ?: current
                    val normalized = RambleActionNormalizer.normalize(revised)
                    if (normalized.type == ActionType.TODO) {
                        sessions.update(session.id) {
                            it.withCards(it.pendingActions.filterNot { card -> card.id == cardId }).copy(
                                warnings = (it.warnings + "That dateless to-do was kept in the note.").distinct()
                            )
                        }
                    } else {
                        sessions.update(session.id) {
                            it.withCards(it.pendingActions.map { card ->
                                if (card.id == cardId) normalized.toPending(cardId) else card
                            })
                        }
                    }
                } catch (cancelled: CancellationException) {
                    throw cancelled
                } catch (_: Throwable) {
                    operationError.value = "Couldn't revise that card. Try again."
                } finally {
                    busyCards.value -= cardId
                }
            }
        }
    }

    /** Removes a card only after its Event/Reminder row was successfully inserted. */
    fun acceptCard(cardId: Long) {
        viewModelScope.launch {
            mutation.withLock { acceptIds(setOf(cardId)) }
        }
    }

    fun acceptAll() {
        viewModelScope.launch {
            mutation.withLock {
                acceptIds(selectedSession()?.pendingActions?.map { it.id }?.toSet().orEmpty())
            }
        }
    }

    /** Retry just the failed durable stage; completed STT/rewrite/attachment work is reused. */
    fun retryPipeline() {
        viewModelScope.launch {
            mutation.withLock {
                val session = selectedSession() ?: return@withLock
                if (session.segments.isEmpty()) {
                    operationError.value = "The recording has no recoverable audio."
                    return@withLock
                }
                sessions.update(session.id) {
                    it.copy(
                        phase = VoiceSessionPhase.RECORDED,
                        actionsExtracted = if (it.actionError != null) false else it.actionsExtracted,
                        actionError = null,
                        reviewDismissed = false,
                        errorCode = null,
                        errorMessage = null
                    )
                }
                operationError.value = null
                reviewVisible.value = true
                RamblePipelineWorker.enqueue(context, session.id, replace = true)
            }
        }
    }

    private suspend fun acceptIds(ids: Set<Long>) {
        if (ids.isEmpty()) return
        val session = selectedSession() ?: return
        val wanted = session.pendingActions.filter { it.id in ids }
        busyCards.value += ids
        operationError.value = null
        val accepted = LinkedHashSet<Long>()
        try {
            for (card in wanted) {
                val action = card.toAction() ?: continue
                val inserted = runCatching {
                    ai.insertAction(action, sourceNoteId = session.targetNoteId)
                }.getOrNull()
                if (inserted != null) accepted += card.id
            }
            sessions.update(session.id) {
                it.withCards(it.pendingActions.filterNot { card -> card.id in accepted })
                    .copy(acceptedCount = it.acceptedCount + accepted.size)
            }
            if (accepted.size != wanted.size) {
                operationError.value = "Some cards couldn't be added. They are still here to retry."
            }
        } finally {
            busyCards.value -= ids
        }
    }

    private fun mutateSession(transform: (VoiceSession) -> VoiceSession) {
        viewModelScope.launch {
            mutation.withLock {
                val session = selectedSession() ?: return@withLock
                sessions.update(session.id, transform)
                operationError.value = null
            }
        }
    }

    private suspend fun selectedSession(): VoiceSession? {
        val id = selectedId.value
        return if (id != null) sessions.get(id)
        else sessions.sessions.value.firstOrNull(::isResumableRamble)
    }

    private fun selectedCommand(command: (Context, String) -> Unit) {
        val id = selectedId.value ?: sessions.sessions.value.firstOrNull(::isResumableRamble)?.id
        id?.let { command(context, it) }
    }

    private fun VoiceSession.withCards(cards: List<PendingVoiceAction>): VoiceSession = copy(
        pendingActions = cards,
        actionError = null,
        phase = if (cards.isEmpty()) VoiceSessionPhase.COMPLETE else VoiceSessionPhase.AWAITING_CONFIRMATION
    )

    private companion object {
        fun isResumableRamble(session: VoiceSession): Boolean =
            session.destination == VoiceDestination.RAMBLE_NOTE &&
                session.phase !in setOf(VoiceSessionPhase.COMPLETE, VoiceSessionPhase.DISCARDED)

        fun PendingVoiceAction.toAction(): ProposedAction? {
            val kind = runCatching { ActionType.valueOf(type) }.getOrNull() ?: return null
            return ProposedAction(kind, title, datetimeMillis, notes)
        }

        fun PendingVoiceAction.toCard(): RambleCard? = toAction()?.let { RambleCard(id, it) }

        fun ProposedAction.toPending(id: Long): PendingVoiceAction = PendingVoiceAction(
            id = id,
            type = type.name,
            title = title,
            datetimeMillis = datetimeMillis,
            notes = notes
        )
    }
}
