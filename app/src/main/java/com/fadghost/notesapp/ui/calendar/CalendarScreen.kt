package com.fadghost.notesapp.ui.calendar

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.fadghost.notesapp.data.db.entity.Recurrence
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.AuraUndoSnackbar
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.shell.LocalNavPillClearance
import com.fadghost.notesapp.ui.shell.NavTab
import com.fadghost.notesapp.ui.shell.ShellSignal
import com.fadghost.notesapp.ui.shell.ShellSignals
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.auraFloatShadow
import com.fadghost.notesapp.ui.theme.auraSheetShadow
import com.fadghost.notesapp.ui.theme.auraTopHighlight
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Calendar tab (PLAN.md §8). Month view with springy swipe + dots, a week strip,
 * a selected-day panel, and an agenda list with sticky day headers — all Aura
 * tokens, no Material. Hosts natural-language quick-add, the create/edit sheet,
 * the notification-permission flow, and the battery-optimisation warning.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun CalendarScreen(
    viewModel: CalendarViewModel = hiltViewModel()
) {
    val tokens = Aura.tokens
    val zone = remember { ZoneId.systemDefault() }
    val data by viewModel.data.collectAsStateWithLifecycle()
    val snackbar by viewModel.snackbar.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val navPillClearance = LocalNavPillClearance.current
    val deepLinkRequest by CalendarDeepLink.pendingRequest.collectAsStateWithLifecycle()

    val today = remember { LocalDate.now(zone) }
    var selectedEpochDay by rememberSaveable { mutableStateOf(today.toEpochDay()) }
    val selected = LocalDate.ofEpochDay(selectedEpochDay)
    // Saveable: the browsed month must survive rotation / process death.
    var visibleMonth by rememberSaveable(
        stateSaver = androidx.compose.runtime.saveable.Saver(
            save = { it.toString() },
            restore = { YearMonth.parse(it) }
        )
    ) { mutableStateOf(YearMonth.from(selected)) }

    var draft by remember { mutableStateOf<ItemDraft?>(null) }
    var agendaExpanded by rememberSaveable { mutableStateOf(true) }

    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    fun openNew() {
        val start = selected.atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        draft = ItemDraft(kind = CalendarKind.EVENT, start = start, end = null)
    }

    // Shell signals: nav re-tap scrolls to top; the contextual FAB starts a new event.
    LaunchedEffect(Unit) {
        ShellSignals.flow.collect { msg ->
            if (msg.tab != NavTab.CALENDAR) return@collect
            when (msg.signal) {
                ShellSignal.SCROLL_TOP -> scope.launch { listState.animateScrollToItem(0) }
                ShellSignal.FAB_PRIMARY -> openNew()
                else -> {}
            }
        }
    }

    // Deep-link from a reminder notification → open its edit sheet.
    LaunchedEffect(deepLinkRequest, data.loaded, data.events, data.reminders) {
        val request = deepLinkRequest ?: return@LaunchedEffect
        if (!data.loaded) return@LaunchedEffect
        draft = when (val target = request.target) {
            is CalendarDeepLinkTarget.Reminder -> data.reminders.firstOrNull { it.id == target.id }?.let { r ->
                ItemDraft(
                    baseId = r.id,
                    kind = CalendarKind.REMINDER,
                    title = r.title,
                    start = r.triggerAt,
                    end = null,
                    recurrence = r.recurrence
                )
            }
            is CalendarDeepLinkTarget.Event -> data.events.firstOrNull { it.id == target.id }?.let { e ->
                ItemDraft(
                    baseId = e.id,
                    kind = CalendarKind.EVENT,
                    title = e.title,
                    start = e.startAt,
                    end = e.endAt,
                    notes = e.notes.orEmpty(),
                    recurrence = e.recurrence,
                    notificationLeadMinutes = e.notificationLeadMinutes
                )
            }
        }
        CalendarDeepLink.consume(request.token)
    }

    // Occurrence expansion window covers the visible grid and ~4 months of agenda.
    val gridStart = visibleMonth.atDay(1).minusDays(7)
    val windowStartMs = remember(gridStart) {
        minOf(gridStart, today).atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val windowEndMs = remember(visibleMonth) {
        maxOf(today.plusDays(120), visibleMonth.atEndOfMonth().plusDays(14))
            .atStartOfDay(zone).toInstant().toEpochMilli()
    }
    val byDay = remember(data, visibleMonth) {
        CalendarExpand.groupByDay(
            CalendarExpand.itemsInRange(data.events, data.reminders, zone, windowStartMs, windowEndMs),
            zone
        )
    }
    // A completed reminder remains visible in its selected-day panel so it can be
    // reopened or un-ticked, but it no longer clutters the forward-looking agenda.
    val agendaByDay = remember(byDay) {
        byDay.mapValues { (_, items) ->
            items.filterNot { it.kind == CalendarKind.REMINDER && it.done }
        }.filterValues { it.isNotEmpty() }
    }
    val agendaDays = remember(agendaByDay, today) {
        agendaByDay.keys.filter { !it.isBefore(today) }.sorted()
    }
    val hasAnything = data.events.isNotEmpty() || data.reminders.isNotEmpty()

    fun editItem(item: CalendarItem) {
        draft = if (item.kind == CalendarKind.EVENT) {
            data.events.firstOrNull { it.id == item.baseId }?.let { e ->
                ItemDraft(
                    baseId = e.id,
                    kind = CalendarKind.EVENT,
                    title = e.title,
                    start = e.startAt,
                    end = e.endAt,
                    notes = e.notes.orEmpty(),
                    recurrence = e.recurrence,
                    notificationLeadMinutes = e.notificationLeadMinutes
                )
            }
        } else {
            data.reminders.firstOrNull { it.id == item.baseId }?.let { r ->
                ItemDraft(
                    baseId = r.id,
                    kind = CalendarKind.REMINDER,
                    title = r.title,
                    start = r.triggerAt,
                    end = null,
                    recurrence = r.recurrence
                )
            }
        }
    }

    Box(Modifier.fillMaxSize().statusBarsPadding().background(tokens.colors.background)) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(
                start = 20.dp, end = 20.dp, top = 4.dp,
                // Reserve the floating nav-pill clearance so the week strip + agenda
                // never render through the pill (systemic inset bug).
                bottom = navPillClearance + 24.dp
            )
        ) {
            // Screen title header — parity with Notes/Diary/Settings (ux.md §3 P0).
            item(key = "header") {
                Column(Modifier.padding(top = 8.dp, bottom = 8.dp)) {
                    BasicText(
                        "YOUR SCHEDULE",
                        style = AuraType.labelSm.copy(color = tokens.colors.textSecondary)
                    )
                    Spacer(Modifier.size(2.dp))
                    BasicText(
                        "Calendar",
                        style = AuraType.titleLg.copy(color = tokens.colors.textPrimary),
                        maxLines = 1,
                        softWrap = false,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
                    )
                }
            }
            item(key = "banners") {
                ReminderSetupCard(context, onPermissionsChanged = viewModel::reschedulePending)
            }
            item(key = "quickadd") {
                QuickAddBar(
                    zone = zone,
                    onConfirm = { r ->
                        val at = r.dateTime.atZone(zone).toInstant().toEpochMilli()
                        viewModel.saveReminder(0L, r.title, at, zone.id, r.recurrence)
                    },
                    modifier = Modifier.padding(bottom = 12.dp)
                )
            }
            item(key = "month") {
                MonthSection(
                    month = visibleMonth,
                    selected = selected,
                    today = today,
                    dotsByDay = byDay,
                    onMonthDelta = { visibleMonth = visibleMonth.plusMonths(it.toLong()) },
                    onSelect = { selectedEpochDay = it.toEpochDay(); visibleMonth = YearMonth.from(it) },
                    onJumpToday = { selectedEpochDay = today.toEpochDay(); visibleMonth = YearMonth.from(today) }
                )
            }
            item(key = "weekstrip") {
                WeekStrip(
                    selected = selected,
                    today = today,
                    byDay = byDay,
                    onSelect = { selectedEpochDay = it.toEpochDay(); visibleMonth = YearMonth.from(it) },
                    modifier = Modifier.padding(vertical = 8.dp)
                )
            }
            item(key = "selectedday") {
                SelectedDayPanel(
                    date = selected,
                    today = today,
                    items = byDay[selected].orEmpty(),
                    zone = zone,
                    onAdd = ::openNew,
                    onEdit = ::editItem,
                    onToggleDone = { viewModel.setReminderDone(it.baseId, !it.done) }
                )
            }

            if (agendaDays.isEmpty() && !hasAnything) {
                item(key = "empty") { CalendarEmptyState() }
            } else {
                item(key = "agendatitle") {
                    val agendaToggleInteraction = remember { MutableInteractionSource() }
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 20.dp, bottom = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        BasicText(
                            "Agenda",
                            style = AuraType.titleSm.copy(color = tokens.colors.textPrimary)
                        )
                        Spacer(Modifier.weight(1f))
                        Box(
                            Modifier
                                .size(44.dp)
                                .clip(RoundedCornerShape(tokens.radii.pill))
                                .semantics { contentDescription = if (agendaExpanded) "Collapse agenda" else "Expand agenda" }
                                .auraPress(agendaToggleInteraction)
                                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
                                .clickable(
                                    interactionSource = agendaToggleInteraction,
                                    indication = null,
                                    onClick = { agendaExpanded = !agendaExpanded }
                                ),
                            contentAlignment = Alignment.Center
                        ) {
                            AuraGlyph(
                                if (agendaExpanded) Glyph.CHEVRON_UP else Glyph.CHEVRON_DOWN,
                                tokens.colors.accent,
                                Modifier.size(20.dp)
                            )
                        }
                    }
                }
                if (agendaExpanded) {
                    agendaDays.forEach { day ->
                        stickyHeader(key = "h_${day.toEpochDay()}") {
                            DayHeader(day, today)
                        }
                        items(agendaByDay[day].orEmpty(), key = { "${it.kind}_${it.baseId}_${it.startMillis}" }) { item ->
                            AgendaRow(
                                item = item, zone = zone,
                                onClick = { editItem(item) },
                                onToggleDone = { viewModel.setReminderDone(item.baseId, !item.done) },
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                }
            }
        }

        // The create action now lives on the shell-level contextual FAB (V2-SPEC item 4).

        ItemDetailSheet(
            draft = draft,
            zone = zone,
            onDismiss = { draft = null },
            onSave = { d ->
                if (d.kind == CalendarKind.EVENT) {
                    viewModel.saveEvent(
                        d.baseId,
                        d.title,
                        d.start,
                        d.end,
                        zone.id,
                        d.notes,
                        d.recurrence,
                        d.notificationLeadMinutes
                    )
                } else {
                    viewModel.saveReminder(d.baseId, d.title, d.start, zone.id, d.recurrence)
                }
            },
            onDelete = { d ->
                if (d.kind == CalendarKind.EVENT) viewModel.deleteEvent(d.baseId)
                else viewModel.deleteReminder(d.baseId)
            }
        )

        // Universal undo snackbar for deletes (ux.md P1-6), clearing the nav pill.
        AuraUndoSnackbar(
            message = snackbar,
            onAction = viewModel::undoDelete,
            onDismiss = viewModel::dismissSnackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navPillClearance)
        )
    }
}

// --- Week strip -------------------------------------------------------------

@Composable
private fun WeekStrip(
    selected: LocalDate,
    today: LocalDate,
    byDay: Map<LocalDate, List<CalendarItem>>,
    onSelect: (LocalDate) -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    val monday = selected.minusDays(((selected.dayOfWeek.value - 1).toLong()))
    val week = (0L..6L).map { monday.plusDays(it) }
    Row(
        modifier
            .fillMaxWidth()
            .auraSheetShadow(RoundedCornerShape(tokens.radii.md))
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
            .padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        week.forEach { d ->
            val isSel = d == selected
            val hasItems = byDay[d].orEmpty().isNotEmpty()
            val dayInteraction = remember { MutableInteractionSource() }
            Column(
                Modifier
                    .weight(1f)
                    // Bound the interactive height so an oversized day cell can never sprawl
                    // down into the floating nav pill's touch band and steal nav taps (P0-1b).
                    .heightIn(max = 72.dp)
                    .clip(RoundedCornerShape(tokens.radii.sm))
                    .auraPress(dayInteraction)
                    .background(if (isSel) tokens.colors.accent else androidx.compose.ui.graphics.Color.Transparent)
                    .clickable(interactionSource = dayInteraction, indication = null, onClick = { onSelect(d) })
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                BasicText(
                    d.dayOfWeek.getDisplayName(java.time.format.TextStyle.NARROW, Locale.getDefault()),
                    style = AuraType.label.copy(color = if (isSel) tokens.colors.background else tokens.colors.textSecondary)
                )
                Spacer(Modifier.size(4.dp))
                BasicText(
                    d.dayOfMonth.toString(),
                    style = AuraType.body.copy(
                        color = when {
                            isSel -> tokens.colors.background
                            d == today -> tokens.colors.accent
                            else -> tokens.colors.textPrimary
                        }
                    )
                )
                Spacer(Modifier.size(3.dp))
                Box(
                    Modifier.size(4.dp).clip(CircleShape).background(
                        if (hasItems) (if (isSel) tokens.colors.background else tokens.colors.accent)
                        else androidx.compose.ui.graphics.Color.Transparent
                    )
                )
            }
        }
    }
}

// --- Selected-day panel -----------------------------------------------------

@Composable
private fun SelectedDayPanel(
    date: LocalDate,
    today: LocalDate,
    items: List<CalendarItem>,
    zone: ZoneId,
    onAdd: () -> Unit,
    onEdit: (CalendarItem) -> Unit,
    onToggleDone: (CalendarItem) -> Unit
) {
    val tokens = Aura.tokens
    Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(dayLabel(date, today), style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
            Spacer(Modifier.weight(1f))
            val addInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .auraPress(addInteraction)
                    .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
                    .clickable(interactionSource = addInteraction, indication = null, onClick = onAdd)
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            ) {
                BasicText("+ Add", style = AuraType.label.copy(color = tokens.colors.accent))
            }
        }
        Spacer(Modifier.size(8.dp))
        if (items.isEmpty()) {
            BasicText(
                "Nothing scheduled.",
                style = AuraType.body.copy(color = tokens.colors.textSecondary),
                modifier = Modifier.padding(vertical = 8.dp)
            )
        } else {
            items.forEach { item ->
                AgendaRow(item, zone, onClick = { onEdit(item) }, onToggleDone = { onToggleDone(item) })
            }
        }
    }
}

// --- Agenda -----------------------------------------------------------------

@Composable
private fun DayHeader(day: LocalDate, today: LocalDate) {
    val tokens = Aura.tokens
    Box(
        Modifier
            .fillMaxWidth()
            .background(tokens.colors.background)
            .padding(vertical = 8.dp)
    ) {
        BasicText(dayLabel(day, today), style = AuraType.label.copy(color = tokens.colors.textSecondary))
    }
}

@Composable
private fun AgendaRow(
    item: CalendarItem,
    zone: ZoneId,
    onClick: () -> Unit,
    onToggleDone: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    val accent = if (item.kind == CalendarKind.EVENT) tokens.colors.accent else tokens.colors.danger
    val rowInteraction = remember { MutableInteractionSource() }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp)
            .auraSheetShadow(RoundedCornerShape(tokens.radii.md))
            .clip(RoundedCornerShape(tokens.radii.md))
            .auraPress(rowInteraction, tint = true)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
            .clickable(interactionSource = rowInteraction, indication = null, onClick = onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(accent))
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            BasicText(
                item.title,
                style = AuraType.body.copy(
                    color = if (item.done) tokens.colors.textSecondary else tokens.colors.textPrimary,
                    textDecoration = if (item.done) TextDecoration.LineThrough else TextDecoration.None
                )
            )
            BasicText(timeLabel(item, zone), style = AuraType.label.copy(color = tokens.colors.textSecondary))
        }
        if (item.isRecurring) {
            Box(
                Modifier
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .background(accent.copy(alpha = 0.14f))
                    .padding(horizontal = 8.dp, vertical = 3.dp)
            ) {
                BasicText(item.recurrence.shortLabel(), style = AuraType.label.copy(color = accent))
            }
        }
        if (item.kind == CalendarKind.REMINDER) {
            Spacer(Modifier.size(10.dp))
            val toggleInteraction = remember { MutableInteractionSource() }
            // 44dp hit box around the 26dp visual disc — a near-miss used to open the
            // edit sheet instead of toggling done (council G9).
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .semantics { contentDescription = if (item.done) "Mark not done" else "Mark done" }
                    .auraPress(toggleInteraction)
                    .clickable(interactionSource = toggleInteraction, indication = null, onClick = onToggleDone),
                contentAlignment = Alignment.Center
            ) {
                Box(
                    Modifier
                        .size(26.dp)
                        .clip(CircleShape)
                        .border(1.5.dp, if (item.done) accent else tokens.colors.outline, CircleShape)
                        .background(if (item.done) accent else androidx.compose.ui.graphics.Color.Transparent),
                    contentAlignment = Alignment.Center
                ) {
                    if (item.done) AuraGlyph(Glyph.CHECK, tokens.colors.background, Modifier.size(16.dp))
                }
            }
        }
    }
}

@Composable
private fun CalendarEmptyState() {
    val tokens = Aura.tokens
    Column(
        Modifier.fillMaxWidth().padding(top = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            Modifier
                .size(56.dp)
                .clip(CircleShape)
                .background(tokens.colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { AuraGlyph(Glyph.CALENDAR, tokens.colors.accent, Modifier.size(30.dp)) }
        Spacer(Modifier.size(14.dp))
        BasicText("Nothing planned yet", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.size(6.dp))
        BasicText(
            "Add an event or reminder — or type \"gym tomorrow 7am\" above.",
            style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
    }
}

// --- Permission + battery banners ------------------------------------------

/**
 * Single collapsed "Set up reminders" card (ux.md P1-8). Replaces the two separate
 * notification-permission + battery-optimisation banners: it shows only while at
 * least one condition is unmet, holds each unmet condition's action inside itself,
 * and is dismissible as a whole. If only one condition is unmet, only that action
 * appears.
 */
@Composable
private fun ReminderSetupCard(context: Context, onPermissionsChanged: () -> Unit) {
    var dismissed by remember { mutableStateOf(false) }
    var notifAsked by remember { mutableStateOf(false) }
    var refreshKey by remember { mutableIntStateOf(0) }
    val lifecycleOwner = LocalLifecycleOwner.current

    // Permission and channel state can change while the OS settings screen covers
    // the app. Refresh on resume, then re-arm rows that could not notify before.
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                refreshKey++
                onPermissionsChanged()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val notifGranted = remember(refreshKey) { canPostReminderNotifications(context) }
    val exactGranted = remember(refreshKey) { canScheduleExactAlarms(context) }
    val ignoringBattery = remember(refreshKey) { isIgnoringBatteryOptimizations(context) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        notifAsked = true
        refreshKey++
        if (result) onPermissionsChanged()
    }

    val runtimeNotifPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
    val notifNeeded = !notifGranted
    val exactNeeded = !exactGranted
    val batteryNeeded = !ignoringBattery
    val criticalSetupNeeded = notifNeeded || exactNeeded
    if ((dismissed && !criticalSetupNeeded) || (!criticalSetupNeeded && !batteryNeeded)) return

    val tokens = Aura.tokens
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 12.dp)
            .auraFloatShadow(RoundedCornerShape(tokens.radii.md))
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
            .auraTopHighlight(tokens.radii.md)
            .padding(16.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BasicText(
                "Set up reminders",
                style = AuraType.bodyLg.copy(color = tokens.colors.textPrimary),
                modifier = Modifier.weight(1f)
            )
            if (!criticalSetupNeeded) {
                BannerAction("Dismiss", subtle = true) { dismissed = true }
            }
        }
        Spacer(Modifier.size(4.dp))
        BasicText(
            "Finish setup so reminders alert you at the right time.",
            style = AuraType.label.copy(color = tokens.colors.textSecondary)
        )

        if (notifNeeded) {
            Spacer(Modifier.size(14.dp))
            BasicText(
                if (notifAsked || !runtimeNotifPermission) "Notifications are off. Enable them in Settings so reminders can alert you."
                else "Reminders need notification access to alert you at the right time.",
                style = AuraType.label.copy(color = tokens.colors.textSecondary)
            )
            Spacer(Modifier.size(8.dp))
            BannerAction(if (notifAsked || !runtimeNotifPermission) "Open notification settings" else "Enable notifications") {
                if (notifAsked || !runtimeNotifPermission) openAppNotificationSettings(context)
                else launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (exactNeeded) {
            Spacer(Modifier.size(14.dp))
            BasicText(
                "Exact alarm access is off. Enable it so time-sensitive reminders and event alerts are not delayed.",
                style = AuraType.label.copy(color = tokens.colors.textSecondary)
            )
            Spacer(Modifier.size(8.dp))
            BannerAction("Enable exact alarms") { openExactAlarmSettings(context) }
        }

        if (batteryNeeded) {
            Spacer(Modifier.size(14.dp))
            BasicText(
                "Battery optimisation may delay alarms. Allow this app to run unrestricted so they fire on time.",
                style = AuraType.label.copy(color = tokens.colors.textSecondary)
            )
            Spacer(Modifier.size(8.dp))
            BannerAction("Fix battery setting") { openBatterySettings(context) }
        }
    }
}

@Composable
private fun BannerAction(label: String, subtle: Boolean = false, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val color = if (subtle) tokens.colors.textSecondary else tokens.colors.accent
    val interaction = remember { MutableInteractionSource() }
    BasicText(
        label,
        style = AuraType.label.copy(color = color),
        modifier = Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 6.dp)
    )
}

// --- Formatting + platform helpers -----------------------------------------

private val TIME_FMT = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())

private fun timeLabel(item: CalendarItem, zone: ZoneId): String {
    val start = Instant.ofEpochMilli(item.startMillis).atZone(zone).toLocalTime().format(TIME_FMT)
    return if (item.kind == CalendarKind.EVENT && item.endMillis != null) {
        val end = Instant.ofEpochMilli(item.endMillis).atZone(zone).toLocalTime().format(TIME_FMT)
        "$start – $end"
    } else start
}

private fun dayLabel(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Today"
    today.plusDays(1) -> "Tomorrow"
    today.minusDays(1) -> "Yesterday"
    else -> day.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.getDefault()))
}

private fun canPostReminderNotifications(context: Context): Boolean {
    val runtimeGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
        androidx.core.content.ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
        android.content.pm.PackageManager.PERMISSION_GRANTED
    if (!runtimeGranted) return false
    val manager = context.getSystemService(NotificationManager::class.java) ?: return false
    if (!manager.areNotificationsEnabled()) return false
    return Build.VERSION.SDK_INT < Build.VERSION_CODES.O ||
        manager.getNotificationChannel(com.fadghost.notesapp.notify.NotificationChannels.REMINDERS)?.importance !=
        NotificationManager.IMPORTANCE_NONE
}

private fun isIgnoringBatteryOptimizations(context: Context): Boolean {
    val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager ?: return true
    return pm.isIgnoringBatteryOptimizations(context.packageName)
}

private fun canScheduleExactAlarms(context: Context): Boolean {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true
    return context.getSystemService(AlarmManager::class.java)?.canScheduleExactAlarms() ?: false
}

private fun openAppNotificationSettings(context: Context) {
    val intent = Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
        .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
}

private fun openBatterySettings(context: Context) {
    // Deep-link to the OS exemption screen (PLAN.md §8).
    val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
}

private fun openExactAlarmSettings(context: Context) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return
    val intent = Intent(
        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
        Uri.parse("package:${context.packageName}")
    ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { context.startActivity(intent) }
        .onFailure {
            runCatching {
                context.startActivity(
                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }
}
