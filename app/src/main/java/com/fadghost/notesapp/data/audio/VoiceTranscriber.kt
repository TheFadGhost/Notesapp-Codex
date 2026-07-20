package com.fadghost.notesapp.data.audio

import com.fadghost.notesapp.data.ai.AiPreferences
import com.fadghost.notesapp.data.ai.ApiKeyStore
import com.fadghost.notesapp.data.ai.cost.AiCallCost
import com.fadghost.notesapp.data.ai.net.OpenRouterClient
import com.fadghost.notesapp.data.ai.net.OpenRouterError
import com.fadghost.notesapp.data.ai.net.Usage
import com.fadghost.notesapp.data.db.dao.AiCostDao
import kotlinx.coroutines.flow.first
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Coarse progress a recording moves through after the user hits Stop (PLAN.md §5). */
sealed interface VoiceProgress {
    data class Uploading(val index: Int, val total: Int) : VoiceProgress
    data class Transcribing(val index: Int, val total: Int) : VoiceProgress
    data object Done : VoiceProgress
}

class PartialVoiceTranscriptionException(
    val completedSegments: Int,
    cause: Throwable
) : Exception("Transcription failed after $completedSegments completed segment(s).", cause)

/**
 * Runs the STT pipeline for a set of recorded segments (PLAN.md §5): each segment is
 * uploaded to `/audio/transcriptions` sequentially with the configured STT model, the
 * per-call cost is recorded (post-hoc usage, PLAN.md §5), and the results are
 * concatenated into one transcript. Cancellation at any stage unwinds the in-flight
 * upload; typed failures reuse [OpenRouterError]. Shared by the recording view-model
 * (online) and the offline queue worker.
 */
@Singleton
class VoiceTranscriber @Inject constructor(
    private val client: OpenRouterClient,
    private val keyStore: ApiKeyStore,
    private val prefs: AiPreferences,
    private val costDao: AiCostDao,
    private val budgetGate: com.fadghost.notesapp.data.ai.AiBudgetGate
) {
    /** STT model id currently configured (PLAN.md §5 default: Qwen3 ASR Flash). */
    suspend fun sttModel(): String = prefs.sttModel.first()

    suspend fun autoClean(): Boolean = prefs.autoCleanTranscript.first()

    /**
     * Transcribe [segments] in order and return the concatenated transcript. Emits
     * [VoiceProgress] per segment via [onProgress]. Throws [OpenRouterError] on failure.
     */
    suspend fun transcribe(
        segments: List<File>,
        noteId: Long?,
        onProgress: (VoiceProgress) -> Unit = {}
    ): String {
        budgetGate.ensureWithinBudget()
        val key = keyStore.get() ?: throw OpenRouterError.InvalidKey
        val model = prefs.sttModel.first()
        val total = segments.size
        val texts = ArrayList<String>(total)
        segments.forEachIndexed { i, file ->
            onProgress(VoiceProgress.Uploading(i + 1, total))
            onProgress(VoiceProgress.Transcribing(i + 1, total))
            val res = try {
                client.transcribe(key, file, model, language = "en")
            } catch (error: Throwable) {
                if (i > 0) throw PartialVoiceTranscriptionException(i, error)
                throw error
            }
            recordCost(res.usage, model, noteId)
            texts += res.text
        }
        onProgress(VoiceProgress.Done)
        return TranscriptText.concatenate(texts)
    }

    private suspend fun recordCost(usage: Usage?, model: String, noteId: Long?) {
        if (usage == null) return
        costDao.insert(
            AiCallCost(
                createdAt = System.currentTimeMillis(),
                feature = FEATURE_TRANSCRIBE,
                model = model,
                promptTokens = usage.promptTokens,
                completionTokens = usage.completionTokens,
                totalTokens = usage.totalTokens,
                costUsd = usage.cost ?: 0.0,
                noteId = noteId
            )
        )
    }

    companion object {
        const val FEATURE_TRANSCRIBE = "transcribe"
    }
}
