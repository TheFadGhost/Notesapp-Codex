package com.fadghost.notesapp.data.ai

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Non-secret AI settings (PLAN.md §5): selected text/STT model ids, favourites,
 * recents, and the last time the /models cache was refreshed. Kept in DataStore
 * (no secrets here — the key lives in [ApiKeyStore]).
 */
private val Context.aiSettingsStore by preferencesDataStore(name = "ai_settings")

@Singleton
class AiPreferences @Inject constructor(
    @ApplicationContext private val context: Context
) {
    private val textModelKey = stringPreferencesKey("text_model")
    private val sttModelKey = stringPreferencesKey("stt_model")
    private val favoritesKey = stringSetPreferencesKey("favorite_models")
    private val recentsKey = stringPreferencesKey("recent_models") // ordered CSV, newest first
    private val modelsFetchedKey = longPreferencesKey("models_fetched_at")
    private val autoCleanTranscriptKey = booleanPreferencesKey("auto_clean_transcript")
    private val monthlyBudgetKey = doublePreferencesKey("monthly_budget_usd")

    val textModel: Flow<String> = context.aiSettingsStore.data.map { it[textModelKey] ?: DEFAULT_TEXT_MODEL }

    /**
     * Selected STT model: a recommended, live-discovered, or custom id. Stored values are
     * trusted unless blank so Settings' explicit Test action, rather than stale app-side
     * assumptions, determines whether a selected model is currently available.
     */
    val sttModel: Flow<String> = context.aiSettingsStore.data.map { prefs ->
        val stored = prefs[sttModelKey]?.trim()
        if (stored.isNullOrBlank()) DEFAULT_STT_MODEL else stored
    }
    val favorites: Flow<Set<String>> = context.aiSettingsStore.data.map { it[favoritesKey] ?: emptySet() }
    val recents: Flow<List<String>> = context.aiSettingsStore.data.map { p ->
        p[recentsKey]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()
    }
    val modelsFetchedAt: Flow<Long> = context.aiSettingsStore.data.map { it[modelsFetchedKey] ?: 0L }

    /**
     * Voice transcript post-processing (PLAN.md §5): OFF = keep verbatim, ON = auto
     * run the M2 Clean-up flow after transcription. Default OFF (verbatim, no key needed).
     */
    val autoCleanTranscript: Flow<Boolean> =
        context.aiSettingsStore.data.map { it[autoCleanTranscriptKey] ?: false }

    suspend fun setTextModel(id: String) {
        context.aiSettingsStore.edit { it[textModelKey] = id.trim() }
        pushRecent(id)
    }

    suspend fun setSttModel(id: String) {
        // Trust any non-blank curated, live-discovered, or hand-typed custom id.
        val trimmed = id.trim()
        val safe = if (trimmed.isBlank()) DEFAULT_STT_MODEL else trimmed
        context.aiSettingsStore.edit { it[sttModelKey] = safe }
    }

    /** Additive favourite (backup restore): stars [id] without ever un-starring. */
    suspend fun favoriteIfAbsent(id: String) {
        context.aiSettingsStore.edit { p ->
            p[favoritesKey] = (p[favoritesKey] ?: emptySet()) + id
        }
    }

    suspend fun toggleFavorite(id: String) {
        context.aiSettingsStore.edit { p ->
            val cur = (p[favoritesKey] ?: emptySet()).toMutableSet()
            if (!cur.add(id)) cur.remove(id)
            p[favoritesKey] = cur
        }
    }

    suspend fun markModelsFetched(now: Long) {
        context.aiSettingsStore.edit { it[modelsFetchedKey] = now }
    }

    suspend fun setAutoCleanTranscript(enabled: Boolean) {
        context.aiSettingsStore.edit { it[autoCleanTranscriptKey] = enabled }
    }

    /** Monthly AI spend cap in USD (IDEAS #26). 0.0 = no cap (the default). */
    val monthlyBudgetUsd: Flow<Double> =
        context.aiSettingsStore.data.map { it[monthlyBudgetKey] ?: 0.0 }

    suspend fun setMonthlyBudgetUsd(capUsd: Double) {
        context.aiSettingsStore.edit { it[monthlyBudgetKey] = capUsd.coerceAtLeast(0.0) }
    }

    private suspend fun pushRecent(id: String) {
        context.aiSettingsStore.edit { p ->
            val cur = (p[recentsKey]?.split("\n")?.filter { it.isNotBlank() } ?: emptyList()).toMutableList()
            cur.remove(id)
            cur.add(0, id)
            p[recentsKey] = cur.take(MAX_RECENTS).joinToString("\n")
        }
    }

    companion object {
        const val DEFAULT_TEXT_MODEL = "deepseek/deepseek-v4-flash"

        /** Cheapest working transcription model (verified live on `/audio/transcriptions`). */
        const val DEFAULT_STT_MODEL = "openai/gpt-4o-mini-transcribe"

        /** Hand-picked text models shown at the top of the Settings picker. */
        val RECOMMENDED_TEXT_MODELS: List<Pair<String, String>> = listOf(
            "z-ai/glm-5.2" to "Z.AI: GLM 5.2",
            "xiaomi/mimo-v2.5" to "Xiaomi: MiMo V2.5",
            "deepseek/deepseek-v4-pro" to "DeepSeek: DeepSeek V4 Pro",
            "xiaomi/mimo-v2.5-pro" to "Xiaomi: MiMo V2.5 Pro",
            "google/gemini-3.1-flash-lite" to "Google: Gemini 3.1 Flash Lite",
            "openai/gpt-5.6-luna" to "OpenAI: GPT-5.6 Luna",
            "anthropic/claude-sonnet-5" to "Anthropic: Claude Sonnet 5"
        )

        /** Hand-picked transcription models shown at the top of the STT picker. */
        val RECOMMENDED_STT_MODELS: List<Pair<String, String>> = listOf(
            "openai/whisper-large-v3-turbo" to "OpenAI: Whisper Large V3 Turbo",
            "openai/whisper-large-v3" to "OpenAI: Whisper Large V3",
            "qwen/qwen3-asr-flash-2026-02-10" to "Qwen: Qwen3 ASR Flash",
            "microsoft/mai-transcribe-1.5" to "Microsoft: MAI Transcribe 1.5",
            "nvidia/parakeet-tdt-0.6b-v3" to "NVIDIA: Parakeet TDT 0.6B V3"
        )

        /**
         * Curated, known-good transcription models with friendly display names (item 9),
         * shown in the STT picker before/alongside a live `/models?output_modalities=
         * transcription` fetch, and used as the fallback if that fetch fails (no key yet,
         * offline). These are NOT returned by the plain `/models` list. Order = cheapest →
         * most accurate. The picker also offers a free-text "custom model id" entry so any
         * other transcription-capable id OpenRouter adds later can be used without an app
         * update — see [OpenRouterClient.listTranscriptionModels] for the dynamic source.
         */
        val CURATED_STT_MODELS: List<Pair<String, String>> =
            RECOMMENDED_STT_MODELS + listOf(
                "openai/gpt-4o-mini-transcribe" to "GPT-4o mini Transcribe",
                "openai/gpt-4o-transcribe" to "GPT-4o Transcribe",
                "openai/whisper-1" to "Whisper v1"
            )
        val STT_MODELS: List<String> = CURATED_STT_MODELS.map { it.first }

        private const val MAX_RECENTS = 8
    }
}
