package com.fadghost.notesapp.data.ai

import com.fadghost.notesapp.data.ai.cost.AiCallCost
import com.fadghost.notesapp.data.ai.cost.CostAccumulator
import com.fadghost.notesapp.data.ai.model.CachedModel
import com.fadghost.notesapp.data.ai.net.ChatMessage
import com.fadghost.notesapp.data.ai.net.ChatRequest
import com.fadghost.notesapp.data.ai.net.OpenRouterClient
import com.fadghost.notesapp.data.ai.net.OpenRouterModel
import com.fadghost.notesapp.data.ai.net.ReasoningRequest
import com.fadghost.notesapp.data.ai.net.ResponseFormat
import com.fadghost.notesapp.data.ai.net.Usage
import com.fadghost.notesapp.alarm.ReminderAlarm
import com.fadghost.notesapp.data.ai.parse.ActionExtractionParser
import com.fadghost.notesapp.data.ai.parse.ExtractOutcome
import com.fadghost.notesapp.data.ai.parse.ProposedAction
import com.fadghost.notesapp.data.ai.text.Chunker
import com.fadghost.notesapp.data.ai.text.TokenEstimator
import com.fadghost.notesapp.data.db.dao.AiCostDao
import com.fadghost.notesapp.data.db.dao.CachedModelDao
import com.fadghost.notesapp.data.db.dao.EventDao
import com.fadghost.notesapp.data.db.dao.ReminderDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Orchestrates the AI layer (PLAN.md §5/§7): key + model management, Clean-up
 * streaming (with long-note map-reduce chunking), Extract structured output with
 * the defensive parser + one automatic re-ask, per-call cost recording, and
 * inserting accepted events/reminders into Room.
 */
@Singleton
class AiRepository @Inject constructor(
    private val client: OpenRouterClient,
    private val keyStore: ApiKeyStore,
    private val prefs: AiPreferences,
    private val costDao: AiCostDao,
    private val modelDao: CachedModelDao,
    private val eventDao: EventDao,
    private val reminderDao: ReminderDao,
    private val connectivity: Connectivity,
    private val alarm: ReminderAlarm
) {
    private val extractionParser = ActionExtractionParser()
    private val inserter = ActionInserter(eventDao, reminderDao, alarm)

    val hasKey: Flow<Boolean> = keyStore.hasKey
    val textModel: Flow<String> = prefs.textModel
    val sttModel: Flow<String> = prefs.sttModel

    /** Voice transcript post-processing toggle (PLAN.md §5): verbatim vs auto clean-up. */
    val autoCleanTranscript: Flow<Boolean> = prefs.autoCleanTranscript
    val favorites: Flow<Set<String>> = prefs.favorites
    val recents: Flow<List<String>> = prefs.recents
    val cachedModels: Flow<List<CachedModel>> = modelDao.observeAll()
    val lastCall: Flow<AiCallCost?> = costDao.observeLast()

    fun isOnline(): Boolean = connectivity.isOnline()

    fun observeMonthTotal(now: Long): Flow<Double> =
        costDao.observeMonthTotal(CostAccumulator.startOfMonth(now, zone()))

    // --- Key management ---------------------------------------------------------

    suspend fun setKey(plaintext: String) = keyStore.set(plaintext)
    suspend fun clearKey() = keyStore.clear()
    suspend fun hasKeyNow(): Boolean = keyStore.hasKeyNow()

    /** Test-connection: validate the pasted (or stored) key against /models. */
    suspend fun testConnection(explicitKey: String? = null): Result<Int> {
        val key = explicitKey?.trim()?.takeIf { it.isNotEmpty() } ?: keyStore.get()
        ?: return Result.failure(IllegalStateException("No key"))
        return client.testConnection(key)
    }

    // --- Models -----------------------------------------------------------------

    /** Fetch /models and cache them; returns the count or a failure. */
    suspend fun refreshModels(now: Long): Result<Int> {
        val key = keyStore.get() ?: return Result.failure(IllegalStateException("No key"))
        return runCatching {
            val models = client.listModels(key)
            modelDao.clear()
            modelDao.upsertAll(models.map { it.toCached(now) })
            prefs.markModelsFetched(now)
            models.size
        }
    }

    suspend fun setTextModel(id: String) = prefs.setTextModel(id)
    suspend fun setSttModel(id: String) = prefs.setSttModel(id)
    suspend fun toggleFavorite(id: String) = prefs.toggleFavorite(id)
    suspend fun setAutoCleanTranscript(enabled: Boolean) = prefs.setAutoCleanTranscript(enabled)

    // --- Clean-up (streaming, map-reduce for long notes) ------------------------

    /**
     * Stream a cleaned version of [text]. Short notes stream directly; long notes
     * are chunked, each cleaned non-streaming (the "map"), then the merge is
     * streamed (the "reduce") so the user still sees live output. Cost from every
     * call is recorded. Throws [com.fadghost.notesapp.data.ai.net.OpenRouterError].
     */
    fun cleanupStream(text: String, noteId: Long?): Flow<OpenRouterClient.Stream> = flow {
        val key = requireKey()
        val model = prefs.textModel.first()
        val context = contextLengthFor(model)

        if (!TokenEstimator.exceedsBudget(text, context)) {
            var usage: Usage? = null
            client.streamCleanup(key, chatRequest(model, AiPrompts.CLEANUP_SYSTEM, text)).collect { ev ->
                if (ev is OpenRouterClient.Stream.Completed) usage = ev.usage
                emit(ev)
            }
            recordCost(usage, model, FEATURE_CLEANUP, noteId)
            return@flow
        }

        // Map: clean each chunk (non-streaming), accumulating cost.
        val budget = ((context * 0.7).toInt() - 1500).coerceAtLeast(500)
        val cleaned = ArrayList<String>()
        for (chunk in Chunker.chunk(text, maxTokens = budget)) {
            val res = client.complete(key, chatRequest(model, AiPrompts.CLEANUP_SYSTEM, chunk))
            recordCost(res.usage, model, FEATURE_CLEANUP, noteId)
            cleaned += res.content
        }
        // Reduce: stream the merge of the cleaned sections.
        var usage: Usage? = null
        client.streamCleanup(
            key,
            chatRequest(model, AiPrompts.CLEANUP_REDUCE_SYSTEM, cleaned.joinToString("\n\n"))
        ).collect { ev ->
            if (ev is OpenRouterClient.Stream.Completed) usage = ev.usage
            emit(ev)
        }
        recordCost(usage, model, FEATURE_CLEANUP, noteId)
    }

    /**
     * Non-streaming Clean-up used by the offline queue worker (no live UI to
     * stream into). Same map-reduce chunking; returns the cleaned Markdown.
     */
    suspend fun cleanupOnce(text: String, noteId: Long?): String {
        val key = requireKey()
        val model = prefs.textModel.first()
        val context = contextLengthFor(model)
        if (!TokenEstimator.exceedsBudget(text, context)) {
            val res = client.complete(key, chatRequest(model, AiPrompts.CLEANUP_SYSTEM, text))
            recordCost(res.usage, model, FEATURE_CLEANUP, noteId)
            return res.content
        }
        val budget = ((context * 0.7).toInt() - 1500).coerceAtLeast(500)
        val cleaned = ArrayList<String>()
        for (chunk in Chunker.chunk(text, maxTokens = budget)) {
            val res = client.complete(key, chatRequest(model, AiPrompts.CLEANUP_SYSTEM, chunk))
            recordCost(res.usage, model, FEATURE_CLEANUP, noteId)
            cleaned += res.content
        }
        val reduce = client.complete(
            key, chatRequest(model, AiPrompts.CLEANUP_REDUCE_SYSTEM, cleaned.joinToString("\n\n"))
        )
        recordCost(reduce.usage, model, FEATURE_CLEANUP, noteId)
        return reduce.content
    }

    // --- Extract (non-streaming structured output) ------------------------------

    /**
     * Extract actions from [text]. Runs on the full text truncated to a safe size
     * (PLAN.md §7). Uses the defensive parser; on parse failure it re-asks once
     * with a stricter nudge, then returns the raw text for the "show raw" state.
     */
    suspend fun extractActions(text: String, noteId: Long?, now: Long): ExtractOutcome {
        val key = requireKey()
        val model = prefs.textModel.first()
        val context = contextLengthFor(model)
        val truncated = truncateForExtract(text, context)
        val wasTruncated = truncated.length < text.length
        val nowIso = AiPrompts.nowIso(now, zone())

        val first = runExtract(key, model, nowIso, truncated, noteId)
        var outcome = extractionParser.parse(first, now, zone())
        if (outcome is ExtractOutcome.ParseFailure) {
            // One automatic re-ask (PLAN.md §5).
            val retry = runExtract(
                key, model, nowIso,
                truncated + "\n\nReturn ONLY the JSON object, nothing else.",
                noteId
            )
            outcome = extractionParser.parse(retry, now, zone())
        }
        return if (wasTruncated && outcome is ExtractOutcome.Success) {
            outcome.copy(warnings = listOf("Note was long — extraction ran on a truncated copy.") + outcome.warnings)
        } else outcome
    }

    private suspend fun runExtract(
        key: String, model: String, nowIso: String, text: String, noteId: Long?
    ): String {
        val req = ChatRequest(
            model = model,
            messages = listOf(
                ChatMessage.system(AiPrompts.extractSystem(nowIso)),
                ChatMessage.user(text)
            ),
            temperature = 0.0,
            maxTokens = MAX_TOKENS,
            reasoning = ReasoningRequest(exclude = true),
            responseFormat = ResponseFormat.jsonSchema("actions", AiPrompts.EXTRACT_SCHEMA, strict = false)
        )
        val res = client.complete(key, req)
        recordCost(res.usage, model, FEATURE_EXTRACT, noteId)
        return res.content
    }

    /** Revise a single [action] using a free-text [instruction] (card "Other"). */
    suspend fun reviseAction(action: ProposedAction, instruction: String, now: Long): ProposedAction? {
        val key = requireKey()
        val model = prefs.textModel.first()
        val nowIso = AiPrompts.nowIso(now, zone())
        val payload = buildString {
            append("Current item: type=${action.type.name.lowercase()}, title=${action.title}")
            action.datetimeMillis?.let { append(", datetime(ms)=$it") }
            action.notes?.let { append(", notes=$it") }
            append("\nInstruction: ").append(instruction)
        }
        val req = ChatRequest(
            model = model,
            messages = listOf(ChatMessage.system(AiPrompts.reviseSystem(nowIso)), ChatMessage.user(payload)),
            temperature = 0.0,
            maxTokens = MAX_TOKENS,
            reasoning = ReasoningRequest(exclude = true)
        )
        val res = client.complete(key, req)
        recordCost(res.usage, model, FEATURE_EXTRACT, action.hashCode().toLong())
        val single = "{\"items\":[${com.fadghost.notesapp.data.ai.parse.JsonExtractor.extract(res.content) ?: return null}]}"
        val parsed = extractionParser.parse(single, now, zone())
        return (parsed as? ExtractOutcome.Success)?.items?.firstOrNull() ?: action
    }

    // --- Inserting accepted actions into Room -----------------------------------

    /**
     * Insert an accepted proposal, arming the exact alarm for reminders. Returns a
     * token identifying the inserted row for undo. Delegates to [ActionInserter].
     */
    suspend fun insertAction(action: ProposedAction): InsertedRow? = inserter.insert(action)

    /** Undo a previously [insertAction]-ed row (batch "Undo all"), cancelling its alarm. */
    suspend fun deleteInserted(row: InsertedRow) = inserter.delete(row)

    // --- Cost -------------------------------------------------------------------

    private suspend fun recordCost(usage: Usage?, model: String, feature: String, noteId: Long?) {
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
                noteId = noteId
            )
        )
    }

    // --- Helpers ----------------------------------------------------------------

    private suspend fun requireKey(): String =
        keyStore.get() ?: throw com.fadghost.notesapp.data.ai.net.OpenRouterError.InvalidKey

    private suspend fun contextLengthFor(model: String): Int =
        modelDao.all().firstOrNull { it.id == model }?.contextLength?.takeIf { it > 0 } ?: DEFAULT_CONTEXT

    private fun truncateForExtract(text: String, context: Int): String {
        val maxChars = ((context * 0.6).toInt() * 3.5).toInt().coerceAtLeast(4000)
        return if (text.length <= maxChars) text else text.take(maxChars)
    }

    private fun chatRequest(model: String, system: String, user: String) = ChatRequest(
        model = model,
        messages = listOf(ChatMessage.system(system), ChatMessage.user(user)),
        temperature = 0.2,
        // Generous ceiling: Clean-up rewrites can be long, and reasoning variants must
        // have room for visible content once chain-of-thought is excluded (item 8).
        maxTokens = CLEANUP_MAX_TOKENS,
        reasoning = ReasoningRequest(exclude = true)
    )

    private fun zone(): ZoneId = ZoneId.systemDefault()

    private fun OpenRouterModel.toCached(now: Long) = CachedModel(
        id = id,
        name = displayName,
        contextLength = contextLength ?: 0,
        promptPrice = pricing?.prompt,
        completionPrice = pricing?.completion,
        inputModalities = architecture?.inputModalities?.joinToString(",") ?: "",
        updatedAt = now
    )

    companion object {
        const val FEATURE_CLEANUP = "cleanup"
        const val FEATURE_EXTRACT = "extract"
        private const val DEFAULT_CONTEXT = 32000

        /** Floor for structured/short calls; reasoning variants need ≥2048 (item 8). */
        private const val MAX_TOKENS = 2048

        /** Clean-up rewrites may be long — give them extra headroom above the floor. */
        private const val CLEANUP_MAX_TOKENS = 4096
    }
}
