package com.fadghost.notesapp.data.ask

import com.fadghost.notesapp.data.ai.AiBudgetGate
import com.fadghost.notesapp.data.ai.AiPreferences
import com.fadghost.notesapp.data.ai.ApiKeyStore
import com.fadghost.notesapp.data.ai.Connectivity
import com.fadghost.notesapp.data.ai.cost.AiCallCost
import com.fadghost.notesapp.data.ai.net.ChatMessage
import com.fadghost.notesapp.data.ai.net.ChatRequest
import com.fadghost.notesapp.data.ai.net.OpenRouterClient
import com.fadghost.notesapp.data.ai.net.OpenRouterError
import com.fadghost.notesapp.data.ai.net.ReasoningRequest
import com.fadghost.notesapp.data.ai.net.ResponseFormat
import com.fadghost.notesapp.data.ai.net.Usage
import com.fadghost.notesapp.data.ai.parse.JsonExtractor
import com.fadghost.notesapp.data.db.dao.AiCostDao
import com.fadghost.notesapp.data.memory.MemoryRepository
import com.fadghost.notesapp.data.repo.NotesRepository
import com.fadghost.notesapp.util.FtsQuery
import com.fadghost.notesapp.util.Markdown
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import javax.inject.Inject
import javax.inject.Singleton

/** Retrieval-augmented Ask pipeline over local note FTS and Folio memory FTS. */
@Singleton
class AskRepository @Inject constructor(
    private val client: OpenRouterClient,
    private val keyStore: ApiKeyStore,
    private val preferences: AiPreferences,
    private val costDao: AiCostDao,
    private val notes: NotesRepository,
    private val memory: MemoryRepository,
    private val connectivity: Connectivity,
    private val budgetGate: AiBudgetGate
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    /** Retrieve context first, then stream an answer grounded in the returned sources. */
    fun ask(question: String, history: List<AskTurn>): Flow<AskStream> = flow {
        if (!connectivity.isOnline()) throw OpenRouterError.Network("offline")
        budgetGate.ensureWithinBudget()
        val key = keyStore.get() ?: throw OpenRouterError.InvalidKey
        val model = preferences.textModel.first()
        val sources = retrieve(question, key, model)
        emit(AskStream.Context(sources))

        val messages = buildList {
            add(ChatMessage.system(AskPrompts.CHAT_SYSTEM_V1))
            add(ChatMessage.system(AskText.contextBlock(sources)))
            history.takeLast(MAX_HISTORY_TURNS).forEach { turn ->
                add(
                    if (turn.role == AskRole.USER) ChatMessage.user(turn.text)
                    else ChatMessage.assistant(turn.text)
                )
            }
            add(ChatMessage.user(question))
        }
        val request = ChatRequest(
            model = model,
            messages = messages,
            temperature = 0.4,
            maxTokens = ANSWER_MAX_TOKENS,
            reasoning = ReasoningRequest(exclude = true)
        )
        var usage: Usage? = null
        client.streamCleanup(key, request).collect { event ->
            when (event) {
                is OpenRouterClient.Stream.Delta -> emit(AskStream.Delta(event.text))
                is OpenRouterClient.Stream.Completed -> usage = event.usage
            }
        }
        recordCost(usage, model, FEATURE_ASK)
        emit(AskStream.Completed)
    }

    private suspend fun retrieve(question: String, key: String, model: String): List<AskSource> {
        val match = FtsQuery.build(question)
        val noteRows = if (match == null) emptyList() else {
            runCatching { notes.search(match).first().take(MAX_NOTE_SOURCES) }.getOrDefault(emptyList())
        }
        val memoryCandidates = runCatching { memory.search(question).take(12) }.getOrDefault(emptyList())
        val memoryCount = runCatching { memory.count.first() }.getOrDefault(0)
        val selectedMemory = when {
            memoryCount == 0 -> emptyList()
            memoryCount < ROUTER_THRESHOLD -> memoryCandidates.take(MAX_MEMORY_SOURCES)
            else -> {
                val index = memory.currentIndex()
                if (index.isBlank()) emptyList()
                else {
                    val slugs = routeMemory(question, index, memoryCandidates.map { it.slug }, key, model)
                    memory.entriesBySlugs(slugs)
                }
            }
        }

        return buildList {
            noteRows.forEach { note ->
                val content = Markdown.strip(note.body).ifBlank { Markdown.stripInline(note.title) }
                add(
                    AskSource(
                        citation = "note:${note.id}",
                        label = note.title.ifBlank { "Untitled note" },
                        excerpt = AskText.excerpt(content),
                        kind = AskSourceKind.NOTE,
                        noteId = note.id
                    )
                )
            }
            selectedMemory.forEach { entry ->
                add(
                    AskSource(
                        citation = entry.slug,
                        label = entry.title,
                        excerpt = AskText.excerpt(entry.body),
                        kind = AskSourceKind.MEMORY,
                        memorySlug = entry.slug
                    )
                )
            }
        }
    }

    private suspend fun routeMemory(
        question: String,
        index: String,
        candidates: List<String>,
        key: String,
        model: String
    ): List<String> {
        val user = buildString {
            append("QUERY:\n").append(question)
            append("\n\nLOCAL CANDIDATES:\n")
            append(candidates.joinToString("\n").ifBlank { "none" })
            append("\n\nVAULT INDEX:\n").append(index)
        }
        val response = client.complete(
            key,
            ChatRequest(
                model = model,
                messages = listOf(
                    ChatMessage.system(AskPrompts.MEMORY_ROUTER_V1),
                    ChatMessage.user(user)
                ),
                temperature = 0.1,
                maxTokens = ROUTER_MAX_TOKENS,
                reasoning = ReasoningRequest(exclude = true),
                responseFormat = ResponseFormat.jsonSchema(
                    name = "memory_router",
                    schema = AskPrompts.ROUTER_SCHEMA,
                    strict = true
                )
            )
        )
        recordCost(response.usage, model, FEATURE_ASK_ROUTER)
        val raw = JsonExtractor.extract(response.content) ?: return emptyList()
        return runCatching { json.decodeFromString<RouterResult>(raw) }
            .getOrNull()?.takeIf { it.found }?.slugs.orEmpty().distinct().take(MAX_MEMORY_SOURCES)
    }

    private suspend fun recordCost(usage: Usage?, model: String, feature: String) {
        if (usage == null) return
        costDao.insert(
            AiCallCost(
                createdAt = System.currentTimeMillis(),
                feature = feature,
                model = model,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                totalTokens = usage.totalTokens,
                costUsd = usage.cost ?: 0.0,
                noteId = null
            )
        )
    }

    @Serializable
    private data class RouterResult(val slugs: List<String> = emptyList(), val found: Boolean = false)

    companion object {
        const val FEATURE_ASK = "ask"
        const val FEATURE_ASK_ROUTER = "ask_router"
        private const val MAX_HISTORY_TURNS = 12
        private const val MAX_NOTE_SOURCES = 8
        private const val MAX_MEMORY_SOURCES = 8
        private const val ROUTER_THRESHOLD = 40
        private const val ROUTER_MAX_TOKENS = 2048
        private const val ANSWER_MAX_TOKENS = 4096
    }
}
