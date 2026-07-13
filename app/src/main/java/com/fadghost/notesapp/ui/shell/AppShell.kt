package com.fadghost.notesapp.ui.shell

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
import com.fadghost.notesapp.data.prefs.ThemeMode
import com.fadghost.notesapp.ui.capture.CaptureLaunch
import com.fadghost.notesapp.ui.capture.CaptureRequest
import com.fadghost.notesapp.ui.DraftRecoveryViewModel
import com.fadghost.notesapp.ui.diary.DiaryNavViewModel
import com.fadghost.notesapp.ui.diary.DiaryScreen
import com.fadghost.notesapp.ui.ask.AskScreen
import com.fadghost.notesapp.ui.editor.EditorScreen
import com.fadghost.notesapp.ui.calendar.CalendarDeepLink
import com.fadghost.notesapp.ui.calendar.CalendarScreen
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.notes.NotesScreen
import com.fadghost.notesapp.ui.screens.SettingsScreen
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.MotionTokens

@Composable
fun AppShell(
    themeMode: ThemeMode,
    onSelectThemeMode: (ThemeMode) -> Unit,
    draftRecovery: DraftRecoveryViewModel = hiltViewModel(),
    diaryNav: DiaryNavViewModel = hiltViewModel(),
    captureVm: com.fadghost.notesapp.ui.capture.CaptureViewModel = hiltViewModel(),
    rambleVm: com.fadghost.notesapp.ui.voice.RambleViewModel = hiltViewModel()
) {
    val tokens = Aura.tokens
    val reduceMotion = LocalReduceMotion.current
    val density = LocalDensity.current
    var selectedTab by rememberSaveable { mutableStateOf(NavTab.NOTES) }
    var captureVisible by remember { mutableStateOf(false) }
    // Editor overlay: null == list; value == open note id (0 == new). Survives config change.
    var editorNoteId by rememberSaveable { mutableStateOf<Long?>(null) }
    var restoringDraft by remember { mutableStateOf(false) }
    var showQuickReminder by remember { mutableStateOf(false) }
    var showVoiceCapture by remember { mutableStateOf(false) }

    // Contextual FAB: behaviour + visibility follow the active tab (V2-SPEC item 4).
    val fabMode = fabModeFor(selectedTab)
    var fabHidden by remember { mutableStateOf(false) }
    LaunchedEffect(selectedTab) { fabHidden = false }

    // Capture paths (PLAN.md §6): tile / shortcuts / share → route into the shell.
    val captureRequest by CaptureLaunch.request.collectAsStateWithLifecycle()
    LaunchedEffect(captureRequest) {
        when (val req = captureRequest) {
            is CaptureRequest.NewNote -> { editorNoteId = 0L; restoringDraft = false }
            is CaptureRequest.OpenNote -> {
                selectedTab = NavTab.NOTES
                editorNoteId = req.noteId
                restoringDraft = false
            }
            // Voice shortcut goes straight to the recording sheet (direct action).
            is CaptureRequest.Voice -> { editorNoteId = null; showVoiceCapture = true }
            is CaptureRequest.TodayDiary -> { selectedTab = NavTab.DIARY; editorNoteId = null }
            is CaptureRequest.SharedText -> captureVm.createNoteFromText(req.text)
            is CaptureRequest.SharedAttachments -> captureVm.createNoteWithAttachments(req.uris)
            null -> {}
        }
        if (captureRequest != null) CaptureLaunch.clear()
    }
    // Shared-text note created off-thread → open it in the editor.
    val sharedNoteId by captureVm.openNoteId.collectAsStateWithLifecycle()
    LaunchedEffect(sharedNoteId) {
        sharedNoteId?.let { id ->
            editorNoteId = id
            restoringDraft = false
            captureVm.consumeOpen()
        }
    }

    // Journaling-nudge deep link (PLAN.md §7): jump to the Diary tab on a genuinely NEW
    // nudge only. The last-handled counter is rememberSaveable so that, after an Activity
    // recreation, a still-elevated request count is NOT replayed onto the restored tab —
    // that replay is what used to bounce the user off Settings after a theme switch (P0-3).
    val diaryRequests by diaryNav.openDiaryRequests.collectAsStateWithLifecycle()
    var handledDiaryRequests by rememberSaveable { mutableStateOf(0) }
    LaunchedEffect(diaryRequests) {
        if (diaryRequests > handledDiaryRequests) {
            handledDiaryRequests = diaryRequests
            selectedTab = NavTab.DIARY
            editorNoteId = null
        }
    }

    // Reminder-notification deep link (PLAN.md §8): jump to Calendar; the screen then
    // opens the item's edit sheet from CalendarDeepLink. Same recreation-replay guard as
    // above (P0-3): a reminder id still sitting in the relay (e.g. its reminder was
    // deleted so CalendarScreen never cleared it) must not re-navigate after a recreation.
    val calendarDeepLink by CalendarDeepLink.pendingRequest.collectAsStateWithLifecycle()
    var handledCalendarToken by rememberSaveable { mutableStateOf(-1L) }
    LaunchedEffect(calendarDeepLink) {
        val request = calendarDeepLink
        if (request == null) {
            // Relay cleared → allow the same id to re-navigate if tapped again later.
            handledCalendarToken = -1L
        } else if (request.token != handledCalendarToken) {
            handledCalendarToken = request.token
            selectedTab = NavTab.CALENDAR
            editorNoteId = null
        }
    }

    val recoverableDraft by draftRecovery.draft.collectAsStateWithLifecycle()

    val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    // Clearance every scrollable tab reserves at its bottom so its last rows clear the
    // floating nav pill instead of rendering through it (systemic collision fix): nav
    // inset + pill height + the pill's bottom margin + a breathing gap.
    val navPillClearance = navInset + NavPillHeight + NavPillBottomMargin + 16.dp

    // Capture-panel "New diary entry" should land like the Diary FAB — focus today +
    // raise the IME (P2-5). We switch tabs, then (once DiaryScreen is subscribed) fire
    // the same FAB_PRIMARY signal it already handles.
    var pendingDiaryFocus by remember { mutableStateOf(false) }
    LaunchedEffect(pendingDiaryFocus, selectedTab) {
        if (pendingDiaryFocus && selectedTab == NavTab.DIARY) {
            ShellSignals.send(NavTab.DIARY, ShellSignal.FAB_PRIMARY)
            pendingDiaryFocus = false
        }
    }

    // Hide-on-scroll: watch child scroll direction and toggle the FAB (ux.md §2).
    val fabNestedScroll = remember {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (available.y < -6f) fabHidden = true
                else if (available.y > 6f) fabHidden = false
                return Offset.Zero
            }
        }
    }

    // Back closes the top overlay instead of exiting the app (V2-SPEC item 12).
    BackHandler(enabled = captureVisible) { captureVisible = false }
    BackHandler(enabled = editorNoteId != null) { editorNoteId = null; restoringDraft = false }

    // One holder preserves each tab's UI state (scroll position, rememberSaveable)
    // across the shared-axis transition (V2-SPEC item 7; platform.md §3).
    val stateHolder = rememberSaveableStateHolder()
    val slidePx = with(density) { 24.dp.roundToPx() }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(tokens.colors.background)
    ) {
        // Content layer. Single-owner insets: each screen applies statusBarsPadding
        // exactly once; the shell no longer pads the top (V2-SPEC item 6).
        Box(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(fabNestedScroll)
        ) {
            CompositionLocalProvider(LocalNavPillClearance provides navPillClearance) {
                AnimatedContent(
                    targetState = selectedTab,
                    transitionSpec = {
                        if (reduceMotion) {
                            fadeIn(tween(120)).togetherWith(fadeOut(tween(120)))
                        } else {
                            val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                            (slideInHorizontally(
                                tween(220, easing = MotionTokens.EmphasizedDecelerate)
                            ) { dir * slidePx } + fadeIn(tween(180)))
                                .togetherWith(
                                    slideOutHorizontally(
                                        tween(200, easing = MotionTokens.EmphasizedDecelerate)
                                    ) { -dir * slidePx / 2 } + fadeOut(tween(140))
                                )
                        }
                    },
                    label = "tab"
                ) { tab ->
                    stateHolder.SaveableStateProvider(tab) {
                        when (tab) {
                            NavTab.NOTES -> NotesScreen(onOpenNote = { editorNoteId = it })
                            NavTab.DIARY -> DiaryScreen()
                            NavTab.CALENDAR -> CalendarScreen()
                            NavTab.ASK -> AskScreen(onOpenNote = { editorNoteId = it })
                            NavTab.SETTINGS -> SettingsScreen(
                                currentMode = themeMode,
                                onSelectMode = onSelectThemeMode
                            )
                        }
                    }
                }
            }
        }

        // Bottom fade: scrolling content dissolves into the background before it reaches
        // the translucent floating pill, instead of showing through it (P0-1). Drawn over
        // the content but under the pill/FAB, and shell-wide so every tab is treated the
        // same. The gradient is fully OPAQUE from the pill's top edge downward (earlier it
        // was still ~60% transparent there, so the week-strip / diary hint bled through the
        // pill); the soft transition now lives entirely ABOVE the pill. Hidden with the
        // pill while the editor overlay is up.
        val pillBandHeight = navInset + NavPillBottomMargin + NavPillHeight
        if (editorNoteId == null) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(navPillClearance + 44.dp)
                    .background(
                        Brush.verticalGradient(
                            0f to Color.Transparent,
                            0.28f to tokens.colors.background,
                            1f to tokens.colors.background
                        )
                    )
            )
            // Tap sink over the pill's touch band: content scrolled transiently behind the
            // translucent pill (e.g. the Calendar week strip) must not steal taps aimed at
            // the nav tabs (P0-1b). The pill + FAB are drawn AFTER this, so they still win
            // their own footprint; only the gaps/margins beside them are absorbed. At rest,
            // content clears this band (contentPadding == navPillClearance), so no real row
            // is ever blocked.
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .height(pillBandHeight)
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {}
                    )
                    .clearAndSetSemantics {}
            )
        }

        // Capture panel (below the FAB in z-order so the FAB stays tappable).
        CapturePanel(
            visible = captureVisible,
            navInset = navInset,
            onDismiss = { captureVisible = false },
            onAction = { action ->
                when (action.label) {
                    "New note" -> editorNoteId = 0L
                    "New diary entry" -> {
                        selectedTab = NavTab.DIARY
                        editorNoteId = null
                        pendingDiaryFocus = true
                    }
                    "Voice ramble" -> showVoiceCapture = true
                    "Quick reminder" -> showQuickReminder = true
                }
            }
        )

        // Floating nav pill + bottom-right capture FAB as ONE centred cluster: the FAB
        // sits on the pill's visual row, a fixed gap guarantees they never overlap at any
        // width, and the FAB's slot is ALWAYS reserved — even on Settings where the FAB is
        // hidden — so the pill's horizontal anchor is identical on every tab (P2-6, was
        // shifting ~95px). Tab slots are computed from the screen width so all four tabs
        // fit and shrink evenly instead of a tab dropping off at 320dp (P0-2). Both fade
        // out while the editor is open.
        val screenWidth = LocalConfiguration.current.screenWidthDp.dp
        // Width-only: identical on every tab so the pill anchor never shifts between tabs.
        // At ultra-narrow widths (≈122dp, the 320px repro) the FAB is dropped so all five
        // tabs keep equal, on-screen, tappable widths instead of clipping off the edge.
        val fabReserved = navShowFab(screenWidth)
        val slotWidth = navTabSlotWidth(screenWidth, fabReserved)
        Row(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = navInset + NavPillBottomMargin),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            AnimatedVisibility(
                visible = editorNoteId == null,
                enter = fadeIn(tween(160)),
                exit = fadeOut(tween(160))
            ) {
                AuraNavBar(
                    selected = selectedTab,
                    slotWidth = slotWidth,
                    onSelect = { tab ->
                        // Re-tap the active tab → scroll that screen to the top (V2-SPEC item 13).
                        if (tab == selectedTab) ShellSignals.send(tab, ShellSignal.SCROLL_TOP)
                        else selectedTab = tab
                    }
                )
            }
            // Reserve the FAB's slot on every tab (kept empty on Settings) so the pill never
            // shifts sideways between tabs. Dropped entirely at ultra-narrow widths where it
            // would push a tab off-screen. The FAB itself only draws when the tab has one.
            if (editorNoteId == null && fabReserved) {
                Spacer(Modifier.width(NavFabGap))
                Box(Modifier.size(NavFabSize), contentAlignment = Alignment.Center) {
                    if (fabMode.visible) {
                        ContextualFab(
                            panelOpen = captureVisible,
                            hidden = fabHidden,
                            onPrimary = {
                                if (captureVisible) {
                                    captureVisible = false
                                } else when (fabMode) {
                                    FabMode.CAPTURE_PANEL -> captureVisible = true
                                    FabMode.DIARY_TODAY -> ShellSignals.send(NavTab.DIARY, ShellSignal.FAB_PRIMARY)
                                    FabMode.CALENDAR_NEW -> ShellSignals.send(NavTab.CALENDAR, ShellSignal.FAB_PRIMARY)
                                    FabMode.HIDDEN -> {}
                                }
                            },
                            onLongPress = { captureVisible = true }
                        )
                    }
                }
            }
        }

        com.fadghost.notesapp.ui.reminder.QuickReminderDialog(
            visible = showQuickReminder,
            onDismiss = { showQuickReminder = false },
            onCreated = { showQuickReminder = false }
        )

        // Voice ramble from the capture panel → transcribe into a fresh note, then open it.
        com.fadghost.notesapp.ui.voice.RambleCaptureSheet(
            visible = showVoiceCapture,
            onDismiss = { showVoiceCapture = false },
            onOpenNote = { id -> showVoiceCapture = false; editorNoteId = id },
            viewModel = rambleVm
        )

        // Editor overlay above the whole shell.
        AnimatedVisibility(
            visible = editorNoteId != null,
            enter = slideInVertically(MotionTokens.mediumFinite(reduceMotion)) { it } +
                fadeIn(MotionTokens.fastFinite(reduceMotion)),
            exit = slideOutVertically(MotionTokens.mediumFinite(reduceMotion)) { it } +
                fadeOut(MotionTokens.fastFinite(reduceMotion)),
            modifier = Modifier.fillMaxSize()
        ) {
            val id = editorNoteId
            if (id != null) {
                EditorScreen(
                    noteId = id,
                    restoreDraft = if (restoringDraft) recoverableDraft else null,
                    onExit = { editorNoteId = null; restoringDraft = false },
                    onDeleted = { deletedId ->
                        editorNoteId = null
                        restoringDraft = false
                        // Existing note → let the Notes list offer undo (P0-2).
                        if (deletedId > 0) ShellSignals.noteDeleted(deletedId)
                    },
                    onOpenAiSettings = {
                        editorNoteId = null; restoringDraft = false; selectedTab = NavTab.SETTINGS
                    }
                )
            }
        }

        // Draft crash-recovery prompt (PLAN.md §6): only on the Notes list, when unsaved
        // text exists. Gated to the Notes tab so it never draws over another tab's heading
        // — it used to flash over the "Calendar" title on entry before settling (P2-7).
        val draft = recoverableDraft
        if (editorNoteId == null && selectedTab == NavTab.NOTES && draft != null && !draft.isEmpty) {
            RestoreDraftBanner(
                title = draft.title.ifBlank { "Untitled note" },
                onRestore = { restoringDraft = true; editorNoteId = draft.noteId },
                onDismiss = { draftRecovery.discard() },
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun RestoreDraftBanner(
    title: String,
    onRestore: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.surfaceTranslucent)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText("Restore unsaved note", style = AuraType.body.copy(color = tokens.colors.textPrimary))
            BasicText(title, style = AuraType.label.copy(color = tokens.colors.textSecondary))
        }
        BannerButton("Restore", tokens.colors.accent, onRestore)
        Spacer(Modifier.width(6.dp))
        BannerButton("Dismiss", tokens.colors.textSecondary, onDismiss)
    }
}

@Composable
private fun BannerButton(label: String, color: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    BasicText(
        text = label,
        style = AuraType.label.copy(color = color),
        modifier = Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 10.dp, vertical = 6.dp)
    )
}
