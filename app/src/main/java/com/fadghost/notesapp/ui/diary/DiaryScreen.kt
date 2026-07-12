package com.fadghost.notesapp.ui.diary

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fadghost.notesapp.data.db.entity.DiaryEntry
import com.fadghost.notesapp.ui.components.FlowChips
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.shell.LocalNavPillClearance
import com.fadghost.notesapp.ui.editor.MarkdownEdits
import com.fadghost.notesapp.ui.editor.MarkdownVisualTransformation
import com.fadghost.notesapp.ui.shell.NavTab
import com.fadghost.notesapp.ui.shell.ShellSignal
import com.fadghost.notesapp.ui.shell.ShellSignals
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.auraSheetShadow
import com.fadghost.notesapp.util.Markdown
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Diary tab (PLAN.md §7): today's entry front-and-centre with the shared markdown
 * editor, a vertical timeline of past days below (infinite scroll), streak counters,
 * a contribution heat-map, "On this day" resurfacing and rotating prompts. Gated by
 * the biometric lock when enabled.
 */
@Composable
fun DiaryScreen(viewModel: DiaryViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val biometricEnabled by viewModel.biometricEnabled.collectAsStateWithLifecycle()
    val locked by viewModel.locked.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) { viewModel.refreshToday() }

    if (biometricEnabled && locked) {
        DiaryBiometricGate(onUnlock = viewModel::unlock)
        return
    }

    var openDay by remember { mutableStateOf<LocalDate?>(null) }

    DiaryContent(
        state = state,
        visibleCount = viewModel.visibleCount.collectAsStateWithLifecycle().value,
        onLoadMore = viewModel::loadMore,
        onSaveTodayDebounced = { body, mood -> viewModel.saveEntry(state.today, body, mood) },
        onSaveTodayNow = { body, mood -> viewModel.saveEntryNow(state.today, body, mood) },
        onOpenDay = { openDay = it }
    )

    openDay?.let { day ->
        DiaryDayEditor(
            date = day,
            viewModel = viewModel,
            onClose = { openDay = null }
        )
    }
}

@Composable
private fun DiaryContent(
    state: DiaryUiState,
    visibleCount: Int,
    onLoadMore: () -> Unit,
    onSaveTodayDebounced: (String, Int?) -> Unit,
    onSaveTodayNow: (String, Int?) -> Unit,
    onOpenDay: (LocalDate) -> Unit
) {
    val tokens = Aura.tokens
    // Systemic inset fix (ux.md): reserve the shared floating-nav-pill clearance so the
    // last entries AND the empty-state (DiaryFirstRun, an item in this list) clear the
    // pill instead of rendering under it.
    val navPillClearance = LocalNavPillClearance.current

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val todayFocus = remember { FocusRequester() }
    // Index of the "today" item (after optional on-this-day + the streaks card).
    val todayIndex = (if (state.onThisDay.isNotEmpty()) 1 else 0) + 1

    // Shell signals: nav re-tap scrolls to top; the FAB jumps to today + raises the IME.
    LaunchedEffect(Unit) {
        ShellSignals.flow.collect { msg ->
            if (msg.tab != NavTab.DIARY) return@collect
            when (msg.signal) {
                ShellSignal.SCROLL_TOP -> scope.launch { listState.animateScrollToItem(0) }
                ShellSignal.FAB_PRIMARY -> scope.launch {
                    listState.animateScrollToItem(todayIndex)
                    runCatching { todayFocus.requestFocus() }
                }
            }
        }
    }

    // Local editable state for today's entry, seeded once per calendar day so that
    // background re-emissions (after autosave) never clobber in-progress typing.
    var todayBody by remember { mutableStateOf(TextFieldValue("")) }
    var todayMood by remember { mutableStateOf<Mood?>(null) }
    var seededDate by remember { mutableStateOf<LocalDate?>(null) }
    LaunchedEffect(state.loaded, state.today) {
        if (state.loaded && seededDate != state.today) {
            val text = state.todayEntry?.body ?: ""
            todayBody = TextFieldValue(text, TextRange(text.length))
            todayMood = Mood.fromScore(state.todayEntry?.mood)
            seededDate = state.today
        }
    }

    val visibleTimeline = state.timeline.take(visibleCount)

    Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                BasicText(
                    "YOUR JOURNAL",
                    style = AuraType.labelSm.copy(color = tokens.colors.textSecondary)
                )
                Spacer(Modifier.height(2.dp))
                BasicText(
                    "Diary",
                    style = AuraType.titleLg.copy(color = tokens.colors.textPrimary),
                    maxLines = 1,
                    softWrap = false
                )
            }
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(
                start = 20.dp, end = 20.dp, top = 4.dp, bottom = navPillClearance
            ),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            // On this day.
            if (state.onThisDay.isNotEmpty()) {
                item(key = "onthisday") {
                    OnThisDayCard(items = state.onThisDay, onOpenDay = onOpenDay)
                }
            }

            // Streaks + heat-map.
            item(key = "streaks") {
                StatsCard(state = state, onOpenDay = onOpenDay)
            }

            // Today's entry (front and centre).
            item(key = "today") {
                TodayCard(
                    date = state.today,
                    body = todayBody,
                    onBodyChange = { new ->
                        val smart = MarkdownEdits.onNewline(todayBody, new) ?: new
                        todayBody = smart
                        onSaveTodayDebounced(smart.text, todayMood?.score)
                    },
                    mood = todayMood,
                    onMoodChange = { m ->
                        todayMood = m
                        onSaveTodayNow(todayBody.text, m?.score)
                    },
                    prompts = state.prompts,
                    onPrompt = { template ->
                        val next = TextFieldValue(template, TextRange(template.length))
                        todayBody = next
                        onSaveTodayDebounced(next.text, todayMood?.score)
                    },
                    bodyFocus = todayFocus
                )
            }

            if (state.timeline.isNotEmpty()) {
                item(key = "timeline-header") {
                    BasicText(
                        "PAST ENTRIES",
                        style = AuraType.labelSm.copy(color = tokens.colors.textSecondary),
                        modifier = Modifier.padding(top = 6.dp, start = 4.dp)
                    )
                }
                items(visibleTimeline, key = { it.id }) { entry ->
                    TimelineCard(entry = entry, onClick = {
                        runCatching { LocalDate.parse(entry.date) }.getOrNull()?.let(onOpenDay)
                    })
                }
                // Infinite scroll: reveal more when the tail is reached (PLAN.md §7).
                if (visibleTimeline.size < state.timeline.size) {
                    item(key = "load-more") {
                        LaunchedEffect(visibleTimeline.size) { onLoadMore() }
                        Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                            BasicText("…", style = AuraType.body.copy(color = tokens.colors.textSecondary))
                        }
                    }
                }
            } else if (!state.hasAnyEntry) {
                item(key = "firstrun") { DiaryFirstRun() }
            }
        }
    }
}

// --- Today ----------------------------------------------------------------------

@Composable
private fun TodayCard(
    date: LocalDate,
    body: TextFieldValue,
    onBodyChange: (TextFieldValue) -> Unit,
    mood: Mood?,
    onMoodChange: (Mood?) -> Unit,
    prompts: List<PromptTemplate>,
    onPrompt: (String) -> Unit,
    bodyFocus: FocusRequester? = null
) {
    val tokens = Aura.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .auraSheetShadow(RoundedCornerShape(tokens.radii.lg))
            .clip(RoundedCornerShape(tokens.radii.lg))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
            .padding(18.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText("TODAY", style = AuraType.labelSm.copy(color = tokens.colors.accent))
                Spacer(Modifier.height(2.dp))
                BasicText(date.format(TODAY_FMT), style = AuraType.titleLg.copy(color = tokens.colors.textPrimary))
            }
        }
        Spacer(Modifier.height(14.dp))
        MoodPicker(selected = mood, onSelect = onMoodChange)
        Spacer(Modifier.height(14.dp))
        DiaryBodyField(body = body, onBodyChange = onBodyChange, placeholder = "Write about your day…", focusRequester = bodyFocus)

        if (body.text.isBlank() && prompts.isNotEmpty()) {
            Spacer(Modifier.height(14.dp))
            BasicText("Need a nudge?", style = AuraType.label.copy(color = tokens.colors.textSecondary))
            Spacer(Modifier.height(8.dp))
            FlowChips {
                prompts.forEach { p ->
                    PromptChip(label = p.chip, onClick = { onPrompt(p.template) })
                }
            }
        }
    }
}

@Composable
private fun DiaryBodyField(
    body: TextFieldValue,
    onBodyChange: (TextFieldValue) -> Unit,
    placeholder: String,
    minHeight: androidx.compose.ui.unit.Dp = 120.dp,
    focusRequester: FocusRequester? = null
) {
    val tokens = Aura.tokens
    val transformation = remember(tokens) {
        MarkdownVisualTransformation(
            textColor = tokens.colors.textPrimary,
            markerColor = tokens.colors.textSecondary,
            accent = tokens.colors.accent
        )
    }
    Box(Modifier.fillMaxWidth().heightIn(min = minHeight)) {
        if (body.text.isEmpty()) {
            BasicText(placeholder, style = AuraType.body.copy(color = tokens.colors.textSecondary))
        }
        BasicTextField(
            value = body,
            onValueChange = onBodyChange,
            textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
            cursorBrush = SolidColor(tokens.colors.accent),
            visualTransformation = transformation,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
        )
    }
}

@Composable
private fun PromptChip(label: String, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .background(tokens.colors.accent.copy(alpha = 0.12f))
            .border(1.dp, tokens.colors.accent.copy(alpha = 0.4f), RoundedCornerShape(tokens.radii.pill))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp)
    ) {
        BasicText(label, style = AuraType.label.copy(color = tokens.colors.accent))
    }
}

// --- Stats: streaks + heat-map --------------------------------------------------

@Composable
private fun StatsCard(state: DiaryUiState, onOpenDay: (LocalDate) -> Unit) {
    val tokens = Aura.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .auraSheetShadow(RoundedCornerShape(tokens.radii.lg))
            .clip(RoundedCornerShape(tokens.radii.lg))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
            .padding(18.dp)
    ) {
        Row(Modifier.fillMaxWidth()) {
            StreakStat("Current streak", state.streaks.current, Modifier.weight(1f))
            StreakStat("Longest streak", state.streaks.longest, Modifier.weight(1f))
        }
        Spacer(Modifier.height(18.dp))
        BasicText("LAST 5 MONTHS", style = AuraType.labelSm.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.height(10.dp))
        DiaryHeatMap(cells = state.heatCells, today = state.today, onOpenDay = onOpenDay)
        Spacer(Modifier.height(10.dp))
        HeatMapLegend()
    }
}

@Composable
private fun StreakStat(label: String, value: Int, modifier: Modifier = Modifier) {
    val tokens = Aura.tokens
    Column(modifier) {
        Row(verticalAlignment = Alignment.Bottom) {
            BasicText("$value", style = AuraType.display.copy(color = tokens.colors.accent))
            Spacer(Modifier.width(6.dp))
            BasicText(
                if (value == 1) "day" else "days",
                style = AuraType.label.copy(color = tokens.colors.textSecondary),
                modifier = Modifier.padding(bottom = 6.dp)
            )
        }
        BasicText(label, style = AuraType.label.copy(color = tokens.colors.textSecondary))
    }
}

// --- On this day ----------------------------------------------------------------

@Composable
private fun OnThisDayCard(items: List<OnThisDayItem>, onOpenDay: (LocalDate) -> Unit) {
    val tokens = Aura.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .auraSheetShadow(RoundedCornerShape(tokens.radii.lg))
            .clip(RoundedCornerShape(tokens.radii.lg))
            .background(tokens.colors.accent.copy(alpha = 0.10f))
            .border(1.dp, tokens.colors.accent.copy(alpha = 0.35f), RoundedCornerShape(tokens.radii.lg))
            .padding(18.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        BasicText("ON THIS DAY", style = AuraType.labelSm.copy(color = tokens.colors.accent))
        items.forEach { item ->
            val date = runCatching { LocalDate.parse(item.entry.date) }.getOrNull()
            val interaction = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(tokens.radii.md))
                    .auraPress(interaction)
                    .clickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = { date?.let(onOpenDay) }
                    )
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    BasicText(item.label, style = AuraType.label.copy(color = tokens.colors.textSecondary))
                    Mood.fromScore(item.entry.mood)?.let {
                        Spacer(Modifier.width(8.dp))
                        MoodBadge(it, Modifier.size(16.dp))
                    }
                }
                BasicText(
                    preview(item.entry.body).ifBlank { "(no words — just a mood)" },
                    style = AuraType.body.copy(color = tokens.colors.textPrimary),
                    maxLines = 2
                )
            }
        }
    }
}

// --- Timeline card --------------------------------------------------------------

@Composable
private fun TimelineCard(entry: DiaryEntry, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val date = runCatching { LocalDate.parse(entry.date) }.getOrNull()
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .auraSheetShadow(RoundedCornerShape(tokens.radii.md))
            .clip(RoundedCornerShape(tokens.radii.md))
            .auraPress(interaction, tint = true)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                BasicText(
                    date?.format(CARD_FMT) ?: entry.date,
                    style = AuraType.bodyLg.copy(color = tokens.colors.textPrimary)
                )
                Mood.fromScore(entry.mood)?.let {
                    Spacer(Modifier.width(8.dp))
                    MoodBadge(it, Modifier.size(18.dp))
                }
            }
            val preview = preview(entry.body)
            if (preview.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                BasicText(
                    preview,
                    style = AuraType.bodySm.copy(color = tokens.colors.textSecondary),
                    maxLines = 2
                )
            }
        }
    }
}

// --- First-run / empty ----------------------------------------------------------

@Composable
private fun DiaryFirstRun() {
    val tokens = Aura.tokens
    Column(
        Modifier.fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BasicText(
            "Your journal starts today",
            style = AuraType.body.copy(color = tokens.colors.textPrimary, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Write a line above, pick a mood, and watch your streak grow.",
            style = AuraType.label.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
    }
}

// --- Past-day editor overlay ----------------------------------------------------

@Composable
private fun DiaryDayEditor(
    date: LocalDate,
    viewModel: DiaryViewModel,
    onClose: () -> Unit
) {
    val tokens = Aura.tokens
    var body by remember(date) { mutableStateOf(TextFieldValue("")) }
    var mood by remember(date) { mutableStateOf<Mood?>(null) }
    var loaded by remember(date) { mutableStateOf(false) }

    LaunchedEffect(date) {
        val entry = viewModel.entryFor(date)
        val text = entry?.body ?: ""
        body = TextFieldValue(text, TextRange(text.length))
        mood = Mood.fromScore(entry?.mood)
        loaded = true
    }

    fun persist() {
        if (loaded) viewModel.saveEntryNow(date, body.text, mood?.score)
    }

    // Back closes the day editor (saving first) instead of exiting the app.
    androidx.activity.compose.BackHandler { persist(); onClose() }

    Box(
        Modifier
            .fillMaxSize()
            .background(tokens.colors.background)
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 40.dp)
        ) {
            Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                BackPill(onClick = { persist(); onClose() })
                Spacer(Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            BasicText(date.format(TODAY_FMT), style = AuraType.titleLg.copy(color = tokens.colors.textPrimary))
            Spacer(Modifier.height(16.dp))
            MoodPicker(selected = mood, onSelect = { m -> mood = m; viewModel.saveEntryNow(date, body.text, m?.score) })
            Spacer(Modifier.height(16.dp))
            DiaryBodyField(
                body = body,
                onBodyChange = { new ->
                    val smart = MarkdownEdits.onNewline(body, new) ?: new
                    body = smart
                    viewModel.saveEntry(date, smart.text, mood?.score)
                },
                placeholder = "Write about this day…",
                minHeight = 240.dp
            )
        }
    }
}

@Composable
private fun BackPill(onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction, tint = true)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        com.fadghost.notesapp.ui.components.AuraGlyph(
            com.fadghost.notesapp.ui.components.Glyph.BACK, tokens.colors.textPrimary, Modifier.size(16.dp)
        )
        Spacer(Modifier.width(6.dp))
        BasicText("Back", style = AuraType.label.copy(color = tokens.colors.textPrimary))
    }
}

// --- helpers --------------------------------------------------------------------

private val TODAY_FMT = DateTimeFormatter.ofPattern("EEEE, d MMMM", Locale.getDefault())
private val CARD_FMT = DateTimeFormatter.ofPattern("EEE, d MMM yyyy", Locale.getDefault())

private fun preview(body: String): String =
    Markdown.strip(body).lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }?.take(120) ?: ""
