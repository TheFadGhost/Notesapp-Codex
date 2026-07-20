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
import com.fadghost.notesapp.data.ai.parse.MemoryExtractOutcome
import com.fadghost.notesapp.data.ai.parse.ProposedAction
import com.fadghost.notesapp.data.ai.parse.ProposedMemoryEntry
import com.fadghost.notesapp.data.ai.work.AiQueueWorker
import com.fadghost.notesapp.data.memory.MemoryFormat
import com.fadghost.notesapp.data.memory.MemoryRepository
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

/** The before/after sheet serves both the light Clean-up and the full Rewrite (P4). */
enum class AiRewriteMode { CLEANUP, REWRITE }

data class CleanupState(
    val active: Boolean = false,
    val streaming: Boolean = false,
    val done: Boolean = false,
    val queued: Boolean = false,
    val before: String = "",
    val after: String = "",
    val error: String? = null,
    val segment: BeforeAfter = BeforeAfter.AFTER,
    val mode: AiRewriteMode = AiRewriteMode.CLEANUP,
    /** Rotating Folio thinking line shown while streaming (V3-DELIGHT §3A). */
    val thinking: String = ""
) {
    val title: String get() = if (mode == AiRewriteMode.REWRITE) "Rewrite" else "Clean up"
}

/** A single "Add to memory" confirm card (V3-PROMPTS.md §1.2 — confirm before any write). */
data class MemoryCard(
    val id: Long,
    val entry: ProposedMemoryEntry,
    val accepted: Boolean = true,
    val editing: Boolean = false
)

data class MemoryState(
    val active: Boolean = false,
    val loading: Boolean = false,
    val thinking: String = "",
    val cards: List<MemoryCard> = emptyList(),
    val skippedReason: String? = null,
    val rawError: String? = null,
    val error: String? = null,
    val savedCount: Int = 0
) {
    val acceptedCount: Int get() = cards.count { it.accepted }
}

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
    private val memoryRepo: MemoryRepository,
    private val resultStore: AiResultStore,
    @ApplicationContext private val appContext: Context
) : ViewModel() {

    val hasKey: StateFlow<Boolean> =
        repo.hasKey.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Whether the memory feature is enabled (Settings toggle) — gates "Add to memory". */
    val memoryEnabled: StateFlow<Boolean> =
        memoryRepo.enabled.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), true)

    /** Whether transcripts should auto-run Clean-up (PLAN.md §5 voice toggle). */
    val autoCleanTranscript: StateFlow<Boolean> =
        repo.autoCleanTranscript.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), false)

    /** Current default text model — feeds the error-card "retry with a different model" chips. */
    val currentTextModel: StateFlow<String> =
        repo.textModel.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), "")

    private val _cleanup = MutableStateFlow(CleanupState())
    val cleanup: StateFlow<CleanupState> = _cleanup.asStateFlow()

    private val _extract = MutableStateFlow(ExtractState())
    val extract: StateFlow<ExtractState> = _extract.asStateFlow()

    private val _memory = MutableStateFlow(MemoryState())
    val memory: StateFlow<MemoryState> = _memory.asStateFlow()

    private val _snackbar = MutableStateFlow<UndoMessage?>(null)
    val snackbar: StateFlow<UndoMessage?> = _snackbar.asStateFlow()

    private var cleanupJob: Job? = null
    private var memoryJob: Job? = null
    private var cardSeq = 0L
    private var noteIdForRun = 0L
    private var extractSourceNoteId: Long? = null
    private var textForRun = ""
    private var extractTextForRun = ""
    private var extractNoteIdForRun = 0L
    private var memoryTextForRun = ""
    private var memoryNoteIdForRun = 0L
    private val insertedRows = ArrayList<InsertedRow>()
    /** The action the current Undo snackbar performs (extract batch OR memory save). */
    private var undoAction: (() -> Unit)? = null
    private var lastMemoryWrite: MemoryRepository.WriteResult? = null

    private val cleanupThinking = listOf("Tidying…", "Straightening up…", "Trimming filler…")
    private val rewriteThinking = listOf("Reading it through…", "Finding the shape…", "Setting it in order…")
    private val memoryThinking = listOf("Noting what matters…", "Marking the durable bits…")

    /** Observe a queued Clean-up result that finished while the editor was closed/offline. */
    fun pendingQueuedCleanup(noteId: Long): StateFlow<String?> =
        resultStore.pendingCleanup(noteId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    // --- Clean-up ---------------------------------------------------------------

    fun startCleanup(noteId: Long, text: String) {
        if (text.isBlank()) return
        noteIdForRun = noteId
        textForRun = text
        _cleanup.value = CleanupState(active = true, before = text, segment = BeforeAfter.AFTER, mode = AiRewriteMode.CLEANUP)
        if (!repo.isOnline()) {
            enqueueOffline(noteId)
            return
        }
        runCleanup()
    }

    /** Rewrite (P4, V3-PROMPTS.md §1.5) — full restructure streamed into the SAME sheet. */
    fun startRewrite(noteId: Long, text: String) {
        if (text.isBlank()) return
        noteIdForRun = noteId
        textForRun = text
        _cleanup.value = CleanupState(active = true, before = text, segment = BeforeAfter.AFTER, mode = AiRewriteMode.REWRITE)
        if (!repo.isOnline()) {
            // Rewrite has no offline queue (unlike Clean-up) — be honest, not silent.
            _cleanup.value = _cleanup.value.copy(error = "You're offline. Rewrite needs a connection — your note's untouched.")
            return
        }
        runRewrite()
    }

    private fun runCleanup() {
        _cleanup.value = _cleanup.value.copy(
            streaming = true, done = false, after = "", error = null, queued = false,
            thinking = cleanupThinking.random()
        )
        streamInto(repo.cleanupStream(textForRun, noteIdForRun.takeIf { it > 0 }))
    }

    private fun runRewrite() {
        _cleanup.value = _cleanup.value.copy(
            streaming = true, done = false, after = "", error = null, queued = false,
            thinking = rewriteThinking.random()
        )
        streamInto(repo.rewriteStream(textForRun, noteIdForRun.takeIf { it > 0 }, System.currentTimeMillis()))
    }

    /** Shared collector for Clean-up + Rewrite streams into the before/after sheet. */
    private fun streamInto(flow: kotlinx.coroutines.flow.Flow<OpenRouterClient.Stream>) {
        cleanupJob?.cancel()
        cleanupJob = viewModelScope.launch {
            try {
                flow.collect { ev ->
                    when (ev) {
                        is OpenRouterClient.Stream.Delta ->
                            _cleanup.value = _cleanup.value.copy(after = _cleanup.value.after + ev.text)
                        is OpenRouterClient.Stream.Completed ->
                            // A reasoning variant can finish with no visible content
                            // (finish_reason "length"). Never present that as a result —
                            // surface a retryable error instead of an empty "after" (item 8).
                            _cleanup.value = if (_cleanup.value.after.isBlank()) {
                                _cleanup.value.copy(
                                    streaming = false,
                                    error = "The model returned nothing. Try again."
                                )
                            } else {
                                _cleanup.value.copy(streaming = false, done = true)
                            }
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
        if (_cleanup.value.mode == AiRewriteMode.REWRITE) {
            if (!repo.isOnline()) {
                _cleanup.value = _cleanup.value.copy(error = "You're offline. Rewrite needs a connection — your note's untouched.")
                return
            }
            runRewrite()
            return
        }
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
        extractTextForRun = text
        extractNoteIdForRun = noteId
        extractSourceNoteId = noteId.takeIf { it > 0 }
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
            repo.insertAction(card.action, extractSourceNoteId)?.let { insertedRows += it }
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
            cards.forEach { c ->
                repo.insertAction(c.action, extractSourceNoteId)?.let { insertedRows += it }
            }
            _extract.value = _extract.value.copy(cards = emptyList(), acceptedCount = _extract.value.acceptedCount + cards.size)
            offerUndoAll()
        }
    }

    private fun offerUndoAll() {
        val n = insertedRows.count()
        if (n > 0) {
            undoAction = { undoInsertedActions() }
            _snackbar.value = UndoMessage("Added $n to your calendar", actionLabel = "Undo all")
        }
    }

    private fun undoInsertedActions() {
        val rows = insertedRows.toList()
        insertedRows.clear()
        viewModelScope.launch { rows.forEach { repo.deleteInserted(it) } }
    }

    /** Snackbar action — runs whichever undo the current message registered. */
    fun performUndo() {
        val action = undoAction
        undoAction = null
        _snackbar.value = null
        action?.invoke()
    }

    fun dismissSnackbar() { _snackbar.value = null; undoAction = null }

    fun dismissExtract() {
        _extract.value = ExtractState()
    }

    // --- Add to memory (P1, confirm-before-write) -------------------------------

    /**
     * Run P1 on the note text against the current index, then present the entries as
     * confirm cards. NOTHING is written until the user taps Keep (V3-PROMPTS.md §1.2).
     */
    fun startAddToMemory(noteId: Long, text: String) {
        if (text.isBlank()) return
        memoryTextForRun = text
        memoryNoteIdForRun = noteId
        _memory.value = MemoryState(active = true, loading = true, thinking = memoryThinking.random())
        memoryJob?.cancel()
        memoryJob = viewModelScope.launch {
            try {
                val index = memoryRepo.currentIndex()
                when (val outcome = repo.extractMemory(text, index, noteId.takeIf { it > 0 }, System.currentTimeMillis())) {
                    is MemoryExtractOutcome.Success -> _memory.value = MemoryState(
                        active = true,
                        cards = outcome.entries.map { MemoryCard(id = cardSeq++, entry = it) },
                        skippedReason = if (outcome.entries.isEmpty()) outcome.skippedReason else null
                    )
                    is MemoryExtractOutcome.ParseFailure -> _memory.value = MemoryState(
                        active = true, rawError = outcome.raw.take(600)
                    )
                }
            } catch (e: Exception) {
                _memory.value = MemoryState(active = true, error = friendly(e))
            }
        }
    }

    fun toggleMemoryCard(id: Long) = mutateMemoryCard(id) { it.copy(accepted = !it.accepted) }
    fun removeMemoryCard(id: Long) {
        _memory.value = _memory.value.copy(cards = _memory.value.cards.filterNot { it.id == id })
    }
    fun beginMemoryEdit(id: Long) = mutateMemoryCard(id) { it.copy(editing = true) }
    fun cancelMemoryEdit(id: Long) = mutateMemoryCard(id) { it.copy(editing = false) }

    fun applyMemoryEdit(id: Long, title: String, body: String) = mutateMemoryCard(id) { card ->
        val m = card.entry.model
        val newBody = MemoryFormat.clampBody(body).ifBlank { m.body }
        card.copy(
            editing = false,
            entry = card.entry.copy(
                model = m.copy(
                    title = title.trim().ifBlank { m.title }.take(120),
                    body = newBody,
                    hook = MemoryFormat.clampHook(m.hook.ifBlank { newBody })
                )
            )
        )
    }

    /** Write the accepted entries to the vault (files + mirror), then offer Undo + Folio copy. */
    fun keepMemory() {
        val accepted = _memory.value.cards.filter { it.accepted }.map { it.entry.model }
        if (accepted.isEmpty()) { dismissMemory(); return }
        viewModelScope.launch {
            val result = runCatching { memoryRepo.writeEntries(accepted) }.getOrNull()
            if (result == null) {
                _memory.value = _memory.value.copy(error = "Couldn't keep those just now. Try again?")
                return@launch
            }
            lastMemoryWrite = result
            val total = memoryRepo.bumpSaveCount(accepted.size)
            _memory.value = _memory.value.copy(cards = emptyList(), savedCount = accepted.size)
            undoAction = { undoMemory() }
            _snackbar.value = UndoMessage(memorySuccessLine(total, accepted.size), actionLabel = "Undo")
        }
    }

    private fun undoMemory() {
        val result = lastMemoryWrite ?: return
        lastMemoryWrite = null
        viewModelScope.launch { memoryRepo.undoWrite(result) }
    }

    /**
     * Folio success copy (V3-DELIGHT §3B): the personified hero line only on the 1st/10th/
     * 50th lifetime save (throttled HARD — repeated it is poison); otherwise a quiet "Kept."
     */
    private fun memorySuccessLine(total: Int, saved: Int): String {
        val prev = total - saved
        val hero = listOf(1, 10, 50).any { it in (prev + 1)..total }
        return if (hero) "Folio remembers the more you keep."
        else listOf("Kept.", "Noted.").random()
    }

    fun dismissMemory() {
        memoryJob?.cancel()
        _memory.value = MemoryState()
    }

    private fun mutateMemoryCard(id: Long, transform: (MemoryCard) -> MemoryCard) {
        _memory.value = _memory.value.copy(
            cards = _memory.value.cards.map { if (it.id == id) transform(it) else it }
        )
    }

    private fun mutateCard(id: Long, transform: (ExtractCard) -> ExtractCard) {
        _extract.value = _extract.value.copy(
            cards = _extract.value.cards.map { if (it.id == id) transform(it) else it }
        )
    }

    // --- Retry with a different model (IDEAS #28) --------------------------------

    /**
     * Error-card model swap: persist the new default text model (same effect as the
     * Settings picker — deliberate, so a broken model stays swapped out), then re-run
     * whichever operation failed. One tap from dead-end to answer.
     */
    fun swapModelRetryCleanup(modelId: String) {
        viewModelScope.launch {
            repo.setTextModel(modelId)
            regenerateCleanup()
        }
    }

    fun swapModelRetryExtract(modelId: String) {
        viewModelScope.launch {
            repo.setTextModel(modelId)
            startExtract(extractNoteIdForRun, extractTextForRun)
        }
    }

    fun swapModelRetryMemory(modelId: String) {
        viewModelScope.launch {
            repo.setTextModel(modelId)
            startAddToMemory(memoryNoteIdForRun, memoryTextForRun)
        }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is OpenRouterError.InvalidKey -> "Your API key was rejected. Check it in Settings."
        is OpenRouterError.NoCredit -> "Your OpenRouter account is out of credit."
        is OpenRouterError.RateLimited -> "Rate limited — please try again in a moment."
        is OpenRouterError.ModelUnavailable -> "That model is unavailable. Pick another in Settings."
        is OpenRouterError.BudgetReached -> "Monthly AI budget reached — raise or clear it in Settings → AI."
        is OpenRouterError.Network -> "AI unavailable — your note is untouched."
        is OpenRouterError.Parse -> "Could not read the model's reply. Try again."
        // Surface the real OpenRouter message (e.g. a rejected request param) instead of
        // masking every failure as generic — the user can see what actually went wrong.
        is OpenRouterError.Unknown -> e.detail?.takeIf { it.isNotBlank() }
            ?.let { "AI error: ${it.take(160)}" }
            ?: "Something went wrong. Your note is untouched."
        else -> "Something went wrong. Your note is untouched."
    }

    companion object {
        fun isTodo(a: ProposedAction) = a.type == ActionType.TODO
    }
}
