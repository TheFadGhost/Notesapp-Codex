package com.fadghost.notesapp.ui.diary

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.fadghost.notesapp.data.db.dao.DiaryDao
import com.fadghost.notesapp.data.db.entity.DiaryEntry
import com.fadghost.notesapp.data.ai.AiRepository
import com.fadghost.notesapp.data.prefs.DiaryPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate
import javax.inject.Inject

/** A resurfaced past entry for the "On this day" card (PLAN.md §7). */
data class OnThisDayItem(val label: String, val entry: DiaryEntry)

/** A gentle rotating journaling prompt shown when today's entry is empty. */
data class PromptTemplate(val chip: String, val template: String)

data class DiaryUiState(
    val today: LocalDate = LocalDate.now(),
    val loaded: Boolean = false,
    val todayEntry: DiaryEntry? = null,
    val timeline: List<DiaryEntry> = emptyList(),
    val streaks: DiaryStreaks = DiaryStreaks(0, 0),
    val heatCells: List<List<HeatCell>> = emptyList(),
    val onThisDay: List<OnThisDayItem> = emptyList(),
    val prompts: List<PromptTemplate> = emptyList(),
    val hasAnyEntry: Boolean = false
)

/** Main-thread registry that debounces each diary date independently. */
internal class DiarySaveJobs {
    private val jobs = mutableMapOf<LocalDate, Job>()

    fun launch(date: LocalDate, scope: CoroutineScope, block: suspend () -> Unit): Job {
        jobs.remove(date)?.cancel()
        val job = scope.launch { block() }
        jobs[date] = job
        job.invokeOnCompletion {
            if (jobs[date] === job) jobs.remove(date)
        }
        return job
    }
}

@HiltViewModel
class DiaryViewModel @Inject constructor(
    private val diaryDao: DiaryDao,
    diaryPreferences: DiaryPreferences,
    private val lockManager: DiaryLockManager,
    private val aiRepository: AiRepository
) : ViewModel() {

    private val today = MutableStateFlow(LocalDate.now())

    /** How many past-timeline entries are revealed (grows on scroll — PLAN.md §7 infinite scroll). */
    private val _visibleCount = MutableStateFlow(PAGE)
    val visibleCount: StateFlow<Int> = _visibleCount

    val biometricEnabled: StateFlow<Boolean> = diaryPreferences.biometricEnabled.stateIn(
        viewModelScope, SharingStarted.WhileSubscribed(5_000), false
    )

    val locked: StateFlow<Boolean> = lockManager.locked

    val state: StateFlow<DiaryUiState> =
        combine(diaryDao.observeAll(), today) { entries, day ->
            build(entries, day)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), DiaryUiState())

    private val saveJobs = DiarySaveJobs()
    private val _cleaningTranscript = MutableStateFlow(false)
    val cleaningTranscript: StateFlow<Boolean> = _cleaningTranscript

    private fun build(entries: List<DiaryEntry>, day: LocalDate): DiaryUiState {
        val byDate = entries.associateBy { it.date }
        val todayIso = day.toString()
        val todayEntry = byDate[todayIso]

        // A day "counts" only when it has real content (body or mood) — keeps streaks honest.
        val active = entries.filter { it.body.isNotBlank() || it.mood != null }
        val activeDates = active.mapNotNull { runCatching { LocalDate.parse(it.date) }.getOrNull() }.toSet()

        val streaks = DiaryStreaks(
            current = DiaryMath.currentStreak(activeDates, day),
            longest = DiaryMath.longestStreak(activeDates)
        )

        val weights = active.mapNotNull { e ->
            runCatching { LocalDate.parse(e.date) }.getOrNull()?.let { it to wordCount(e.body) }
        }.toMap()
        val heat = DiaryMath.heatCells(end = day, weeks = HEATMAP_WEEKS, weightByDate = weights)

        val anchors = DiaryMath.onThisDay(day)
        val onThisDay = buildList {
            byDate[anchors.monthAgo.toString()]?.takeIf { it.body.isNotBlank() || it.mood != null }
                ?.let { add(OnThisDayItem("1 month ago", it)) }
            byDate[anchors.yearAgo.toString()]?.takeIf { it.body.isNotBlank() || it.mood != null }
                ?.let { add(OnThisDayItem("1 year ago", it)) }
        }

        val timeline = entries.filter { it.date != todayIso }

        return DiaryUiState(
            today = day,
            loaded = true,
            todayEntry = todayEntry,
            timeline = timeline,
            streaks = streaks,
            heatCells = heat,
            onThisDay = onThisDay,
            prompts = rotatingPrompts(day),
            hasAnyEntry = active.isNotEmpty()
        )
    }

    /** Re-read "today" (e.g. after midnight or returning to the app). */
    fun refreshToday() {
        today.value = LocalDate.now()
    }

    fun loadMore() {
        _visibleCount.value += PAGE
    }

    suspend fun entryFor(date: LocalDate): DiaryEntry? = diaryDao.getByDate(date.toString())

    /** Debounced save while typing (PLAN.md §6 autosave pattern). */
    fun saveEntry(date: LocalDate, body: String, mood: Int?) {
        saveJobs.launch(date, viewModelScope) {
            kotlinx.coroutines.delay(SAVE_DEBOUNCE_MS)
            persist(date, body, mood)
        }
    }

    /** Immediate save (mood taps, editor close). */
    fun saveEntryNow(date: LocalDate, body: String, mood: Int?) {
        saveJobs.launch(date, viewModelScope) { persist(date, body, mood) }
    }

    /** Raw speech is inserted first; cleanup only runs after the visible Make it clean action. */
    fun cleanTranscript(raw: String, onResult: (Result<String>) -> Unit) {
        if (raw.isBlank() || _cleaningTranscript.value) return
        viewModelScope.launch {
            _cleaningTranscript.value = true
            val result = runCatching { aiRepository.cleanDiaryTranscript(raw) }
            _cleaningTranscript.value = false
            onResult(result)
        }
    }

    private suspend fun persist(date: LocalDate, body: String, mood: Int?) {
        val iso = date.toString()
        val now = System.currentTimeMillis()
        val existing = diaryDao.getByDate(iso)
        // Nothing to store and no prior row -> skip creating an empty entry.
        if (existing == null && body.isBlank() && mood == null) return
        val entry = existing?.copy(body = body, mood = mood, updatedAt = now)
            ?: DiaryEntry(date = iso, body = body, mood = mood, createdAt = now, updatedAt = now)
        diaryDao.upsert(entry)
    }

    fun unlock() = lockManager.unlock()

    private fun rotatingPrompts(day: LocalDate): List<PromptTemplate> {
        val start = day.dayOfYear % PROMPTS.size
        return (0 until 3).map { PROMPTS[(start + it) % PROMPTS.size] }
    }

    companion object {
        const val HEATMAP_WEEKS = 22 // ~5 months (PLAN.md §7)
        private const val PAGE = 15
        private const val SAVE_DEBOUNCE_MS = 500L

        val PROMPTS = listOf(
            PromptTemplate("How was today?", "How was today?\n\n"),
            PromptTemplate("Grateful for…", "Today I'm grateful for:\n- "),
            PromptTemplate("One small win", "One small win today was "),
            PromptTemplate("On my mind", "What's on my mind right now:\n\n"),
            PromptTemplate("Tomorrow I'll…", "Tomorrow I want to:\n- "),
            PromptTemplate("How I feel", "Right now I feel ")
        )

        fun wordCount(body: String): Int =
            body.trim().split(Regex("\\s+")).count { it.isNotBlank() }
    }
}
