package com.fadghost.notesapp.ui.ai

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.ai.AiRepository
import com.fadghost.notesapp.data.ai.AiResultStore
import com.fadghost.notesapp.data.ai.InsertedRow
import com.fadghost.notesapp.data.ai.net.OpenRouterClient
import com.fadghost.notesapp.data.ai.net.OpenRouterError
import com.fadghost.notesapp.data.ai.parse.ActionType
import com.fadghost.notesapp.data.ai.parse.ExtractOutcome
import com.fadghost.notesapp.data.ai.parse.ProposedAction
import com.fadghost.notesapp.data.ai.work.AiQueueWorker
import com.fadghost.notesapp.ui.components.UndoMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Which segment of the before/after sheet is shown. */
enum class BeforeAfter { BEFORE, AFTER }

data class CleanupState(
    val active: Boolean = false,
    val streaming: Boolean = false,
    val done: Boolean = false,
    val queued: Boolean = false,
    val before: String = "",
    val after: String = "",
    val error: String? = null,
    val segment: BeforeAfter = BeforeAfter.AFTER
)

/** A single extracted-action confirmation card. */
data class ExtractCard(
    val id: Long,
    val action: ProposedAction,
    val editing: Boolean = false,
    val revising: Boolean = false
)

data class ExtractState(
    val active: Boolean = false,
    val loading: Boolean = false,
    val cards: List<ExtractCard> = emptyList(),
    val warnings: List<String> = emptyList(),
    val rawError: String? = null,
    val error: String? = null,
    val acceptedCount: Int = 0
)

/**
 * Editor-scoped AI controller (PLAN.md §5): Clean-up streaming into a before/after
 * sheet, Extract structured output into confirmation cards, no-key gating, offline
 * queueing, and the batch Undo-all. Text is passed in from the editor composable;
 * accepted Clean-up text is returned via [onAccepted] so the editor's own undo
 * stack owns the change.
 */
@HiltViewModel
class EditorAiViewModel @Inject constructor(
    private val repo: AiRepository,
    private val resultStore: AiResultStore,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    val hasKey: StateFlow<Boolean> =
        repo.hasKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Whether transcripts should auto-run Clean-up (PLAN.md §5 voice toggle). */
    val autoCleanTranscript: StateFlow<Boolean> =
        repo.autoCleanTranscript.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    private val _cleanup = MutableStateFlow(CleanupState())
    val cleanup: StateFlow<CleanupState> = _cleanup.asStateFlow()

    private val _extract = MutableStateFlow(ExtractState())
    val extract: StateFlow<ExtractState> = _extract.asStateFlow()

    private val _snackbar = MutableStateFlow<UndoMessage?>(null)
    val snackbar: StateFlow<UndoMessage?> = _snackbar.asStateFlow()

    private var cleanupJob: Job? = null
    private var cardSeq = 0L
    private var noteIdForRun = 0L
    private var textForRun = ""
    private val insertedRows = ArrayList<InsertedRow>()

    /** Observe a queued Clean-up result that finished while the editor was closed/offline. */
    fun pendingQueuedCleanup(noteId: Long): StateFlow<String?> =
        resultStore.pendingCleanup(noteId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // --- Clean-up ---------------------------------------------------------------

    fun startCleanup(noteId: Long, text: String) {
        if (text.isBlank()) return
        noteIdForRun = noteId
        textForRun = text
        _cleanup.value = CleanupState(active = true, before = text, segment = BeforeAfter.AFTER)
        if (!repo.isOnline()) {
            enqueueOffline(noteId)
            return
        }
        runCleanup()
    }

    private fun runCleanup() {
        _cleanup.value = _cleanup.value.copy(streaming = true, done = false, after = "", error = null, queued = false)
        cleanupJob?.cancel()
        cleanupJob = viewModelScope.launch {
            try {
                repo.cleanupStream(textForRun, noteIdForRun.takeIf { it > 0 }).collect { ev ->
                    when (ev) {
                        is OpenRouterClient.Stream.Delta ->
                            _cleanup.value = _cleanup.value.copy(after = _cleanup.value.after + ev.text)
                        is OpenRouterClient.Stream.Completed ->
                            _cleanup.value = _cleanup.value.copy(streaming = false, done = true)
                    }
                }
            } catch (e: Exception) {
                _cleanup.value = _cleanup.value.copy(streaming = false, error = friendly(e))
            }
        }
    }

    private fun enqueueOffline(noteId: Long) {
        _cleanup.value = _cleanup.value.copy(queued = true, streaming = false)
        if (noteId > 0) AiQueueWorker.enqueueCleanup(appContext, noteId)
    }

    fun regenerateCleanup() {
        if (!repo.isOnline()) { enqueueOffline(noteIdForRun); return }
        runCleanup()
    }

    fun cancelCleanup() {
        cleanupJob?.cancel()
        _cleanup.value = _cleanup.value.copy(streaming = false)
    }

    fun setSegment(seg: BeforeAfter) {
        _cleanup.value = _cleanup.value.copy(segment = seg)
    }

    fun dismissCleanup() {
        cleanupJob?.cancel()
        _cleanup.value = CleanupState()
    }

    /** Accept the cleaned text; caller applies it to the editor body (undoable). */
    fun acceptCleanup(): String? {
        val after = _cleanup.value.after.takeIf { it.isNotBlank() } ?: return null
        _cleanup.value = CleanupState()
        return after
    }

    fun applyQueuedResult(noteId: Long) {
        viewModelScope.launch { resultStore.clearCleanup(noteId) }
    }

    fun dismissQueuedResult(noteId: Long) {
        viewModelScope.launch { resultStore.clearCleanup(noteId) }
    }

    // --- Extract ----------------------------------------------------------------

    fun startExtract(noteId: Long, text: String) {
        if (text.isBlank()) return
        insertedRows.clear()
        _extract.value = ExtractState(active = true, loading = true)
        viewModelScope.launch {
            try {
                when (val outcome = repo.extractActions(text, noteId.takeIf { it > 0 }, System.currentTimeMillis())) {
                    is ExtractOutcome.Success -> _extract.value = ExtractState(
                        active = true,
                        cards = outcome.items.map { ExtractCard(id = cardSeq++, action = it) },
                        warnings = outcome.warnings
                    )
                    is ExtractOutcome.ParseFailure -> _extract.value = ExtractState(
                        active = true,
                        rawError = outcome.raw.take(600)
                    )
                }
            } catch (e: Exception) {
                _extract.value = ExtractState(active = true, error = friendly(e))
            }
        }
    }

    fun rejectCard(id: Long) {
        _extract.value = _extract.value.copy(cards = _extract.value.cards.filterNot { it.id == id })
    }

    fun beginEdit(id: Long) = mutateCard(id) { it.copy(editing = true) }
    fun cancelEdit(id: Long) = mutateCard(id) { it.copy(editing = false) }

    fun applyEdit(id: Long, title: String, datetimeMillis: Long?) = mutateCard(id) {
        it.copy(editing = false, action = it.action.copy(title = title.trim().ifBlank { it.action.title }, datetimeMillis = datetimeMillis))
    }

    fun reviseCard(id: Long, instruction: String) {
        if (instruction.isBlank()) return
        val card = _extract.value.cards.firstOrNull { it.id == id } ?: return
        mutateCard(id) { it.copy(revising = true) }
        viewModelScope.launch {
            val revised = runCatching {
                repo.reviseAction(card.action, instruction, System.currentTimeMillis())
            }.getOrNull()
            mutateCard(id) { it.copy(revising = false, action = revised ?: it.action) }
        }
    }

    fun acceptCard(id: Long) {
        val card = _extract.value.cards.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            repo.insertAction(card.action)?.let { insertedRows += it }
            _extract.value = _extract.value.copy(
                cards = _extract.value.cards.filterNot { it.id == id },
                acceptedCount = _extract.value.acceptedCount + 1
            )
            if (_extract.value.cards.isEmpty()) offerUndoAll()
        }
    }

    fun acceptAll() {
        val cards = _extract.value.cards
        if (cards.isEmpty()) return
        viewModelScope.launch {
            cards.forEach { c -> repo.insertAction(c.action)?.let { insertedRows += it } }
            _extract.value = _extract.value.copy(cards = emptyList(), acceptedCount = _extract.value.acceptedCount + cards.size)
            offerUndoAll()
        }
    }

    private fun offerUndoAll() {
        val n = insertedRows.count()
        if (n > 0) _snackbar.value = UndoMessage("Added $n to your calendar", actionLabel = "Undo all")
    }

    fun undoAll() {
        val rows = insertedRows.toList()
        insertedRows.clear()
        _snackbar.value = null
        viewModelScope.launch { rows.forEach { repo.deleteInserted(it) } }
    }

    fun dismissSnackbar() { _snackbar.value = null }

    fun dismissExtract() {
        _extract.value = ExtractState()
    }

    private fun mutateCard(id: Long, transform: (ExtractCard) -> ExtractCard) {
        _extract.value = _extract.value.copy(
            cards = _extract.value.cards.map { if (it.id == id) transform(it) else it }
        )
    }

    private fun friendly(e: Throwable): String = when (e) {
        is OpenRouterError.InvalidKey -> "Your API key was rejected. Check it in Settings."
        is OpenRouterError.NoCredit -> "Your OpenRouter account is out of credit."
        is OpenRouterError.RateLimited -> "Rate limited — please try again in a moment."
        is OpenRouterError.ModelUnavailable -> "That model is unavailable. Pick another in Settings."
        is OpenRouterError.Network -> "AI unavailable — your note is untouched."
        is OpenRouterError.Parse -> "Could not read the model's reply. Try again."
        else -> "Something went wrong. Your note is untouched."
    }

    companion object {
        fun isTodo(a: ProposedAction) = a.type == ActionType.TODO
    }
}
