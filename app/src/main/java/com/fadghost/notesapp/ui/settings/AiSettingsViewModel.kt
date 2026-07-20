package com.fadghost.notesapp.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.ai.AiPreferences
import com.fadghost.notesapp.data.ai.AiRepository
import com.fadghost.notesapp.data.ai.model.CachedModel
import com.fadghost.notesapp.data.ai.net.OpenRouterError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Settings → AI view-model (PLAN.md §5): key paste/test/clear, model pickers with
 * the cached /models list + favourites/recents, and the cost read-out.
 */
@HiltViewModel
class AiSettingsViewModel @Inject constructor(
    private val repo: AiRepository
) : ViewModel() {

    private val now = System.currentTimeMillis()

    val hasKey: StateFlow<Boolean> = repo.hasKey.state(false)
    val textModel: StateFlow<String> = repo.textModel.state("")
    val sttModel: StateFlow<String> = repo.sttModel.state("")
    val models: StateFlow<List<CachedModel>> = repo.cachedModels.state(emptyList())
    val favorites: StateFlow<Set<String>> = repo.favorites.state(emptySet())
    val recents: StateFlow<List<String>> = repo.recents.state(emptyList())
    val monthTotal: StateFlow<Double> = repo.observeMonthTotal(now).state(0.0)
    val lastCall = repo.lastCall.state(null)
    val autoCleanTranscript: StateFlow<Boolean> = repo.autoCleanTranscript.state(false)
    /** Monthly AI budget cap in USD; 0 = off (IDEAS #26). */
    val monthlyBudget: StateFlow<Double> = repo.monthlyBudgetUsd.state(0.0)

    private val _status = MutableStateFlow<String?>(null)
    val status: StateFlow<String?> = _status.asStateFlow()

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    /**
     * STT model picker list (item 9): starts as the curated trio, replaced by the
     * live `/models?output_modalities=transcription` result once [refreshSttModels]
     * succeeds — that live set is exactly what `/audio/transcriptions` accepts, so it
     * self-heals if OpenRouter retires/renames an id (the failure mode that produced
     * the old hardcoded-trio-only picker). Falls back to the curated trio again if a
     * refresh returns nothing usable.
     */
    private val _sttModels = MutableStateFlow(curatedSttModels())
    val sttModels: StateFlow<List<CachedModel>> = _sttModels.asStateFlow()

    private val _sttTestStatus = MutableStateFlow<String?>(null)
    val sttTestStatus: StateFlow<String?> = _sttTestStatus.asStateFlow()

    fun saveKey(key: String) {
        if (key.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            repo.setKey(key)
            _status.value = "Key saved"
            _busy.value = false
        }
    }

    fun clearKey() {
        viewModelScope.launch {
            repo.clearKey()
            _status.value = "Key cleared"
        }
    }

    /** Validate a pasted [key] (or the stored one) against /models. */
    fun testConnection(key: String?) {
        viewModelScope.launch {
            _busy.value = true
            _status.value = "Testing…"
            val result = repo.testConnection(key)
            _status.value = result.fold(
                onSuccess = { "✓ Connected — $it models available" },
                onFailure = { "✗ ${friendly(it)}" }
            )
            // On success, cache the list too.
            if (result.isSuccess) repo.refreshModels(now)
            _busy.value = false
        }
    }

    fun refreshModels() {
        viewModelScope.launch {
            _busy.value = true
            _status.value = repo.refreshModels(now).fold(
                onSuccess = { "Loaded $it models" },
                onFailure = { "✗ ${friendly(it)}" }
            )
            _busy.value = false
        }
    }

    fun setTextModel(id: String) { if (id.isNotBlank()) viewModelScope.launch { repo.setTextModel(id.trim()) } }
    fun setSttModel(id: String) { if (id.isNotBlank()) viewModelScope.launch { repo.setSttModel(id.trim()) } }
    fun toggleFavorite(id: String) { viewModelScope.launch { repo.toggleFavorite(id) } }
    fun setAutoCleanTranscript(enabled: Boolean) { viewModelScope.launch { repo.setAutoCleanTranscript(enabled) } }
    fun setMonthlyBudget(capUsd: Double) { viewModelScope.launch { repo.setMonthlyBudgetUsd(capUsd) } }

    /** Live-refresh the STT picker list from `/models?output_modalities=transcription`. */
    fun refreshSttModels() {
        viewModelScope.launch {
            _busy.value = true
            repo.refreshSttModels().fold(
                onSuccess = { fetched ->
                    _sttModels.value = mergeWithCuratedSttModels(fetched)
                    _status.value = "Loaded ${fetched.size} transcription models"
                },
                onFailure = { _status.value = "✗ ${friendly(it)}" }
            )
            _busy.value = false
        }
    }

    /** Settings STT "Test": probes [model] against the live endpoint (item 9). */
    fun testSttModel(model: String) {
        if (model.isBlank()) return
        viewModelScope.launch {
            _busy.value = true
            _sttTestStatus.value = "Testing…"
            _sttTestStatus.value = repo.testSttModel(model).fold(
                onSuccess = { "✓ \"$model\" works" },
                onFailure = { "✗ ${friendly(it)}" }
            )
            _busy.value = false
        }
    }

    private fun curatedSttModels(): List<CachedModel> =
        AiPreferences.CURATED_STT_MODELS.map { (id, name) -> CachedModel(id = id, name = name) }

    /**
     * Keep the bundled options (including the unchanged default) available after a live
     * refresh. Where OpenRouter returned the same id, prefer its current name and metadata.
     */
    private fun mergeWithCuratedSttModels(live: List<CachedModel>): List<CachedModel> {
        val liveById = live.associateBy { it.id }
        val curated = curatedSttModels()
        return curated.map { model -> liveById[model.id] ?: model } +
            live.filterNot { model -> curated.any { it.id == model.id } }
    }

    private fun friendly(e: Throwable): String = when (e) {
        is OpenRouterError.InvalidKey -> "Key rejected"
        is OpenRouterError.NoCredit -> "Out of credit"
        is OpenRouterError.RateLimited -> "Rate limited"
        is OpenRouterError.Network -> "No connection"
        is OpenRouterError.ModelUnavailable -> "Model \"${e.model}\" does not exist"
        else -> e.message ?: "Failed"
    }

    private fun <T> kotlinx.coroutines.flow.Flow<T>.state(initial: T): StateFlow<T> =
        stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)
}
