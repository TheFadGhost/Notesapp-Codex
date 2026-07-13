package com.fadghost.notesapp.ui.ask

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.ai.AiRepository
import com.fadghost.notesapp.data.ai.InsertedRow
import com.fadghost.notesapp.data.ai.net.OpenRouterError
import com.fadghost.notesapp.data.ai.parse.ExtractOutcome
import com.fadghost.notesapp.data.ai.parse.MemoryExtractOutcome
import com.fadghost.notesapp.data.ask.AskMarkerParser
import com.fadghost.notesapp.data.ask.AskRepository
import com.fadghost.notesapp.data.ask.AskRole
import com.fadghost.notesapp.data.ask.AskSource
import com.fadghost.notesapp.data.ask.AskStream
import com.fadghost.notesapp.data.ask.AskText
import com.fadghost.notesapp.data.ask.AskTurn
import com.fadghost.notesapp.data.memory.MemoryFormat
import com.fadghost.notesapp.data.memory.MemoryRepository
import com.fadghost.notesapp.ui.ai.ExtractCard
import com.fadghost.notesapp.ui.ai.ExtractState
import com.fadghost.notesapp.ui.ai.MemoryCard
import com.fadghost.notesapp.ui.ai.MemoryState
import com.fadghost.notesapp.ui.components.UndoMessage
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AskMessage(
    val id: Long,
    val role: AskRole,
    val text: String,
    val sources: List<AskSource> = emptyList(),
    val streaming: Boolean = false
)

data class AskUiState(
    val messages: List<AskMessage> = emptyList(),
    val working: Boolean = false,
    val error: String? = null,
    val selectedSource: AskSource? = null,
    val extract: ExtractState = ExtractState(),
    val memory: MemoryState = MemoryState(),
    val snackbar: UndoMessage? = null
)

@HiltViewModel
class AskViewModel @Inject constructor(
    private val repository: AskRepository,
    private val aiRepository: AiRepository,
    private val memoryRepository: MemoryRepository
) : ViewModel() {
    private val _state = MutableStateFlow(AskUiState())
    val state: StateFlow<AskUiState> = _state.asStateFlow()

    private var sequence = 1L
    private var cardSequence = 1L
    private var askJob: Job? = null
    private var actionJob: Job? = null
    private var memoryJob: Job? = null
    private var pendingExtractTranscript: String? = null
    private val insertedRows = ArrayList<InsertedRow>()
    private var lastMemoryWrite: MemoryRepository.WriteResult? = null
    private var undoAction: (() -> Unit)? = null

    fun send(rawQuestion: String) {
        val question = rawQuestion.trim()
        if (question.isEmpty() || _state.value.working) return

        val prior = _state.value.messages
        val history = prior.filter { !it.streaming && it.text.isNotBlank() }
            .map { AskTurn(it.role, it.text) }
        val user = AskMessage(sequence++, AskRole.USER, question)
        val answerId = sequence++
        val answer = AskMessage(answerId, AskRole.ASSISTANT, "", streaming = true)
        _state.value = _state.value.copy(
            messages = prior + user + answer,
            working = true,
            error = null
        )

        askJob?.cancel()
        askJob = viewModelScope.launch {
            val pending = StringBuilder()
            var availableSources = emptyList<AskSource>()
            var lastFlush = 0L

            fun flush(force: Boolean = false) {
                if (pending.isEmpty()) return
                val now = SystemClock.elapsedRealtime()
                if (!force && now - lastFlush < FRAME_MS) return
                val chunk = pending.toString()
                pending.clear()
                lastFlush = now
                mutateMessage(answerId) { it.copy(text = it.text + chunk) }
            }

            try {
                repository.ask(question, history).collect { event ->
                    when (event) {
                        is AskStream.Context -> availableSources = event.sources
                        is AskStream.Delta -> {
                            pending.append(event.text)
                            flush()
                        }
                        AskStream.Completed -> flush(force = true)
                    }
                }
                flush(force = true)
                val completed = _state.value.messages.firstOrNull { it.id == answerId }
                if (completed?.text.isNullOrBlank()) {
                    throw IllegalStateException("The model returned no visible answer")
                }
                val markers = AskMarkerParser.parse(completed!!.text)
                val citedSources = AskText.citedSources(markers.visibleText, availableSources)
                val visibleText = AskText.withoutCitationTokens(markers.visibleText).ifBlank {
                    "I've prepared that for your review."
                }
                mutateMessage(answerId) { message ->
                    message.copy(
                        text = visibleText,
                        streaming = false,
                        sources = citedSources
                    )
                }
                _state.value = _state.value.copy(working = false, error = null)
                val transcript = actionTranscript()
                when {
                    markers.saveMemoryFact != null -> {
                        pendingExtractTranscript = transcript.takeIf { markers.extractActions }
                        startMemoryConfirmation(markers.saveMemoryFact)
                    }
                    markers.extractActions -> startExtractConfirmation(transcript)
                }
            } catch (cancelled: CancellationException) {
                flush(force = true)
                mutateMessage(answerId) { it.copy(streaming = false) }
                throw cancelled
            } catch (e: Exception) {
                flush(force = true)
                mutateMessage(answerId) { it.copy(streaming = false) }
                _state.value = _state.value.copy(working = false, error = friendly(e))
            }
        }
    }

    fun cancel() {
        askJob?.cancel()
        askJob = null
        val streaming = _state.value.messages.lastOrNull { it.streaming }
        if (streaming != null) mutateMessage(streaming.id) { it.copy(streaming = false) }
        _state.value = _state.value.copy(working = false, error = null)
    }

    fun retryLast() {
        if (_state.value.working) return
        val messages = _state.value.messages
        val lastUserIndex = messages.indexOfLast { it.role == AskRole.USER }
        if (lastUserIndex < 0) return
        val question = messages[lastUserIndex].text
        _state.value = _state.value.copy(messages = messages.take(lastUserIndex), error = null)
        send(question)
    }

    fun clearConversation() {
        if (_state.value.working) cancel()
        actionJob?.cancel()
        memoryJob?.cancel()
        pendingExtractTranscript = null
        insertedRows.clear()
        lastMemoryWrite = null
        undoAction = null
        _state.value = AskUiState()
    }

    fun selectSource(source: AskSource?) {
        _state.value = _state.value.copy(selectedSource = source)
    }

    // --- EXTRACT_ACTIONS confirm flow ------------------------------------------

    private fun startExtractConfirmation(transcript: String) {
        if (transcript.isBlank()) return
        insertedRows.clear()
        _state.value = _state.value.copy(extract = ExtractState(active = true, loading = true))
        actionJob?.cancel()
        actionJob = viewModelScope.launch {
            try {
                when (val outcome = aiRepository.extractActions(transcript, null, System.currentTimeMillis())) {
                    is ExtractOutcome.Success -> _state.value = _state.value.copy(
                        extract = ExtractState(
                            active = true,
                            cards = outcome.items.map { ExtractCard(cardSequence++, it) },
                            warnings = outcome.warnings
                        )
                    )
                    is ExtractOutcome.ParseFailure -> _state.value = _state.value.copy(
                        extract = ExtractState(active = true, rawError = outcome.raw.take(600))
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    extract = ExtractState(active = true, error = friendly(error))
                )
            }
        }
    }

    fun rejectAction(id: Long) {
        _state.value = _state.value.copy(
            extract = _state.value.extract.copy(cards = _state.value.extract.cards.filterNot { it.id == id })
        )
    }

    fun beginActionEdit(id: Long) = mutateAction(id) { it.copy(editing = true) }
    fun cancelActionEdit(id: Long) = mutateAction(id) { it.copy(editing = false) }

    fun applyActionEdit(id: Long, title: String, datetimeMillis: Long?) = mutateAction(id) {
        it.copy(
            editing = false,
            action = it.action.copy(
                title = title.trim().ifBlank { it.action.title },
                datetimeMillis = datetimeMillis
            )
        )
    }

    fun reviseAction(id: Long, instruction: String) {
        if (instruction.isBlank()) return
        val card = _state.value.extract.cards.firstOrNull { it.id == id } ?: return
        mutateAction(id) { it.copy(revising = true) }
        viewModelScope.launch {
            val revised = runCatching {
                aiRepository.reviseAction(card.action, instruction, System.currentTimeMillis())
            }.getOrNull()
            mutateAction(id) { it.copy(revising = false, action = revised ?: it.action) }
        }
    }

    fun acceptAction(id: Long) {
        val card = _state.value.extract.cards.firstOrNull { it.id == id } ?: return
        viewModelScope.launch {
            try {
                val inserted = aiRepository.insertAction(card.action)
                    ?: throw IllegalStateException("That item can't be added yet.")
                insertedRows += inserted
                val extract = _state.value.extract
                _state.value = _state.value.copy(
                    extract = extract.copy(
                        cards = extract.cards.filterNot { it.id == id },
                        acceptedCount = extract.acceptedCount + 1,
                        error = null
                    )
                )
                if (_state.value.extract.cards.isEmpty()) offerActionUndo()
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    extract = _state.value.extract.copy(error = friendly(error))
                )
            }
        }
    }

    fun acceptAllActions() {
        val cards = _state.value.extract.cards
        if (cards.isEmpty()) return
        viewModelScope.launch {
            val accepted = mutableSetOf<Long>()
            var failure: Throwable? = null
            cards.forEach { card ->
                try {
                    val inserted = aiRepository.insertAction(card.action)
                        ?: throw IllegalStateException("That item can't be added yet.")
                    insertedRows += inserted
                    accepted += card.id
                } catch (error: Exception) {
                    failure = failure ?: error
                }
            }
            val extract = _state.value.extract
            _state.value = _state.value.copy(
                extract = extract.copy(
                    cards = extract.cards.filterNot { it.id in accepted },
                    acceptedCount = extract.acceptedCount + accepted.size,
                    error = failure?.let(::friendly)
                )
            )
            if (_state.value.extract.cards.isEmpty()) offerActionUndo()
        }
    }

    fun dismissExtract() {
        actionJob?.cancel()
        _state.value = _state.value.copy(extract = ExtractState())
    }

    private fun mutateAction(id: Long, block: (ExtractCard) -> ExtractCard) {
        val extract = _state.value.extract
        _state.value = _state.value.copy(
            extract = extract.copy(cards = extract.cards.map { if (it.id == id) block(it) else it })
        )
    }

    private fun offerActionUndo() {
        val rows = insertedRows.toList()
        if (rows.isEmpty()) return
        undoAction = {
            insertedRows.clear()
            viewModelScope.launch { rows.forEach { aiRepository.deleteInserted(it) } }
        }
        _state.value = _state.value.copy(
            snackbar = UndoMessage("Added ${rows.size} to your calendar", "Undo all")
        )
    }

    // --- SAVE_MEMORY confirm flow ----------------------------------------------

    private fun startMemoryConfirmation(fact: String) {
        _state.value = _state.value.copy(
            memory = MemoryState(active = true, loading = true, thinking = "Noting what matters\u2026")
        )
        memoryJob?.cancel()
        memoryJob = viewModelScope.launch {
            try {
                val index = memoryRepository.currentIndex()
                when (val outcome = aiRepository.extractMemory(fact, index, null, System.currentTimeMillis())) {
                    is MemoryExtractOutcome.Success -> _state.value = _state.value.copy(
                        memory = MemoryState(
                            active = true,
                            cards = outcome.entries.map { proposed ->
                                MemoryCard(
                                    id = cardSequence++,
                                    entry = proposed.copy(
                                        model = proposed.model.copy(source = MemoryFormat.SOURCE_CHAT)
                                    )
                                )
                            },
                            skippedReason = outcome.skippedReason.takeIf { outcome.entries.isEmpty() }
                        )
                    )
                    is MemoryExtractOutcome.ParseFailure -> _state.value = _state.value.copy(
                        memory = MemoryState(active = true, rawError = outcome.raw.take(600))
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    memory = MemoryState(active = true, error = friendly(error))
                )
            }
        }
    }

    fun toggleMemory(id: Long) = mutateMemory(id) { it.copy(accepted = !it.accepted) }

    fun removeMemory(id: Long) {
        val memory = _state.value.memory
        _state.value = _state.value.copy(
            memory = memory.copy(cards = memory.cards.filterNot { it.id == id })
        )
    }

    fun beginMemoryEdit(id: Long) = mutateMemory(id) { it.copy(editing = true) }
    fun cancelMemoryEdit(id: Long) = mutateMemory(id) { it.copy(editing = false) }

    fun applyMemoryEdit(id: Long, title: String, body: String) = mutateMemory(id) { card ->
        val model = card.entry.model
        val newBody = MemoryFormat.clampBody(body).ifBlank { model.body }
        card.copy(
            editing = false,
            entry = card.entry.copy(
                model = model.copy(
                    title = title.trim().ifBlank { model.title }.take(120),
                    body = newBody,
                    hook = MemoryFormat.clampHook(model.hook.ifBlank { newBody }),
                    source = MemoryFormat.SOURCE_CHAT
                )
            )
        )
    }

    fun keepMemory() {
        val accepted = _state.value.memory.cards.filter { it.accepted }.map { it.entry.model }
        if (accepted.isEmpty()) {
            dismissMemory()
            return
        }
        memoryJob = viewModelScope.launch {
            try {
                val write = memoryRepository.writeEntries(accepted)
                lastMemoryWrite = write
                memoryRepository.bumpSaveCount(accepted.size)
                undoAction = {
                    lastMemoryWrite = null
                    viewModelScope.launch { memoryRepository.undoWrite(write) }
                }
                _state.value = _state.value.copy(
                    memory = MemoryState(),
                    snackbar = UndoMessage(if (accepted.size == 1) "Kept." else "Kept ${accepted.size} memories.")
                )
                startPendingExtractIfAny()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                _state.value = _state.value.copy(
                    memory = _state.value.memory.copy(error = friendly(error))
                )
            }
        }
    }

    fun dismissMemory() {
        memoryJob?.cancel()
        _state.value = _state.value.copy(memory = MemoryState())
        startPendingExtractIfAny()
    }

    private fun mutateMemory(id: Long, block: (MemoryCard) -> MemoryCard) {
        val memory = _state.value.memory
        _state.value = _state.value.copy(
            memory = memory.copy(cards = memory.cards.map { if (it.id == id) block(it) else it })
        )
    }

    private fun startPendingExtractIfAny() {
        pendingExtractTranscript?.let { transcript ->
            pendingExtractTranscript = null
            startExtractConfirmation(transcript)
        }
    }

    fun performUndo() {
        val action = undoAction
        undoAction = null
        _state.value = _state.value.copy(snackbar = null)
        action?.invoke()
    }

    fun dismissSnackbar() {
        undoAction = null
        _state.value = _state.value.copy(snackbar = null)
    }

    private fun actionTranscript(): String = _state.value.messages.takeLast(12).joinToString("\n") {
        val speaker = if (it.role == AskRole.USER) "User" else "Folio"
        "$speaker: ${it.text}"
    }

    private fun mutateMessage(id: Long, block: (AskMessage) -> AskMessage) {
        _state.value = _state.value.copy(
            messages = _state.value.messages.map { if (it.id == id) block(it) else it }
        )
    }

    private fun friendly(error: Throwable): String = when (error) {
        is OpenRouterError.InvalidKey -> "Add or check your OpenRouter key in Settings."
        is OpenRouterError.NoCredit -> "Your OpenRouter credit has run out."
        is OpenRouterError.RateLimited -> "Folio is being rate-limited. Try again in a moment."
        is OpenRouterError.ModelUnavailable -> "That model can't be reached. Pick another in Settings."
        is OpenRouterError.Network -> "You're offline. Your conversation is still here."
        is OpenRouterError.Parse -> "That answer came back garbled. Try once more?"
        is OpenRouterError.Unknown -> error.detail?.take(180) ?: "Folio couldn't answer that just now."
        else -> error.message?.takeIf { it.isNotBlank() }?.take(180)
            ?: "Folio couldn't answer that just now."
    }

    private companion object {
        /** UI state is updated at most ~30 Hz even when SSE delivers faster. */
        const val FRAME_MS = 34L
    }
}
