package com.fadghost.notesapp.ui.editor

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.TagChip
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.util.UndoStack
import kotlinx.coroutines.launch

/**
 * Markdown editor (PLAN.md §6): live-styled body, sticky toolbar, smart lists,
 * tappable checkboxes, undo/redo, tag/folder assignment. Autosave + draft
 * recovery live in [EditorViewModel].
 */
@Composable
fun EditorScreen(
    noteId: Long,
    onExit: () -> Unit,
    restoreDraft: com.fadghost.notesapp.data.prefs.DraftSnapshot? = null,
    onDeleted: (Long) -> Unit = { onExit() },
    onOpenAiSettings: () -> Unit = {},
    viewModel: EditorViewModel = hiltViewModel(),
    aiViewModel: com.fadghost.notesapp.ui.ai.EditorAiViewModel = hiltViewModel(),
    audioViewModel: com.fadghost.notesapp.ui.voice.EditorAudioViewModel = hiltViewModel(),
    voiceViewModel: com.fadghost.notesapp.ui.voice.VoiceRecordViewModel = hiltViewModel()
) {
    val tokens = Aura.tokens
    val state by viewModel.state.collectAsStateWithLifecycle()
    val folders by viewModel.folders.collectAsStateWithLifecycle()
    val allTags by viewModel.allTags.collectAsStateWithLifecycle()
    val noteTags by viewModel.noteTags.collectAsStateWithLifecycle()
    val haptics = LocalHapticFeedback.current
    val focus = LocalFocusManager.current
    val bodyFocus = remember { FocusRequester() }

    val hasKey by aiViewModel.hasKey.collectAsStateWithLifecycle()
    val cleanupState by aiViewModel.cleanup.collectAsStateWithLifecycle()
    val extractState by aiViewModel.extract.collectAsStateWithLifecycle()
    val aiSnackbar by aiViewModel.snackbar.collectAsStateWithLifecycle()
    val autoCleanTranscript by aiViewModel.autoCleanTranscript.collectAsStateWithLifecycle()
    val queuedResult by remember(state.noteId) { aiViewModel.pendingQueuedCleanup(state.noteId) }.collectAsStateWithLifecycle()

    val audioChips by audioViewModel.chips.collectAsStateWithLifecycle()
    var showVoiceSheet by remember { mutableStateOf(false) }
    var voiceTargetNoteId by remember { mutableStateOf(0L) }
    var openPlayer by remember { mutableStateOf<com.fadghost.notesapp.data.db.entity.AudioAttachment?>(null) }

    LaunchedEffect(noteId) { viewModel.open(noteId, restoreDraft) }
    LaunchedEffect(state.noteId) { audioViewModel.bind(state.noteId) }

    var titleValue by remember { mutableStateOf(TextFieldValue("")) }
    var bodyValue by remember { mutableStateOf(TextFieldValue("")) }
    var initializedFor by remember { mutableStateOf(-1L) }
    var showPicker by remember { mutableStateOf(false) }
    var showNoKey by remember { mutableStateOf(false) }
    var bodyLayout by remember { mutableStateOf<TextLayoutResult?>(null) }

    // One-time coach tip labelling the three AI icons (P1-3), DataStore-gated.
    val context = LocalContext.current
    val coachStore = remember { EditorCoachStore(context) }
    val coachScope = rememberCoroutineScope()
    val coachSeen by coachStore.seen.collectAsStateWithLifecycle(initialValue = true)
    var coachVisible by remember { mutableStateOf(false) }
    LaunchedEffect(coachSeen, state.loaded) {
        if (state.loaded && EditorCoachGate.shouldShow(coachSeen)) coachVisible = true
    }

    // Seed the fields once the VM has loaded (also runs after process death).
    LaunchedEffect(state.loaded, state.noteId) {
        if (state.loaded && initializedFor != state.noteId) {
            titleValue = TextFieldValue(state.initialTitle, TextRange(state.initialTitle.length))
            bodyValue = TextFieldValue(state.initialBody, TextRange(state.initialBody.length))
            initializedFor = state.noteId
            // New, blank note from the capture sheet: pop the keyboard immediately.
            if (state.noteId == 0L && state.initialBody.isEmpty() && state.initialTitle.isEmpty()) {
                runCatching { bodyFocus.requestFocus() }
            }
        }
    }

    fun applyBody(newValue: TextFieldValue, coalesce: UndoStack.CoalesceKey) {
        bodyValue = newValue
        viewModel.onBodyChanged(newValue.text, coalesce)
    }

    fun onCleanup() {
        if (hasKey) { focus.clearFocus(); aiViewModel.startCleanup(state.noteId, bodyValue.text) }
        else showNoKey = true
    }

    fun onExtract() {
        if (hasKey) { focus.clearFocus(); aiViewModel.startExtract(state.noteId, bodyValue.text) }
        else showNoKey = true
    }

    // Voice ramble appended to the open note (PLAN.md §5 — editor toolbar mic).
    fun onVoice() {
        if (!hasKey) { showNoKey = true; return }
        focus.clearFocus()
        viewModel.ensureSaved { id -> voiceTargetNoteId = id; showVoiceSheet = true }
    }

    // Insert the finished transcript at the caret, then record its audio attachment
    // anchored at the transcript line start (the chip's home).
    fun insertTranscript(transcript: String) {
        val existing = bodyValue.text
        val caret = bodyValue.selection.end.coerceIn(0, existing.length)
        val prefix = if (caret > 0 && existing[caret - 1] != '\n') "\n" else ""
        val insert = prefix + transcript
        val newText = existing.substring(0, caret) + insert + existing.substring(caret)
        val tStart = caret + prefix.length
        val tEnd = tStart + transcript.length
        applyBody(TextFieldValue(newText, TextRange(tEnd)), UndoStack.CoalesceKey.BOUNDARY)
        voiceViewModel.commitEditorAttachment(tStart, tEnd)
        // Optional auto clean-up runs the existing M2 flow (PLAN.md §5 toggle).
        if (autoCleanTranscript) aiViewModel.startCleanup(state.noteId, newText)
    }

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
                .padding(bottom = 96.dp)
        ) {
            // Top bar.
            Row(
                Modifier.fillMaxWidth().padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconAction(Glyph.BACK) {
                    focus.clearFocus()
                    viewModel.close()
                    onExit()
                }
                Spacer(Modifier.width(4.dp))
                IconAction(Glyph.SPARKLE, tint = tokens.colors.accent) { onCleanup() }
                IconAction(Glyph.CALENDAR, tint = tokens.colors.accent) { onExtract() }
                MicAction(tint = tokens.colors.accent) { onVoice() }
                Spacer(Modifier.weight(1f))
                PillAction(
                    glyph = Glyph.FOLDER,
                    label = folders.firstOrNull { it.id == state.folderId }?.name ?: "Folder"
                ) { showPicker = true }
                Spacer(Modifier.width(8.dp))
                PillAction(glyph = Glyph.TAG, label = "Tags") { showPicker = true }
                // Keep the destructive trash well clear of the Tags pill (P0-2): a wide
                // gap + a hairline divider so it can't be fat-fingered for "Tags".
                Spacer(Modifier.width(14.dp))
                Box(
                    Modifier
                        .height(24.dp)
                        .width(1.dp)
                        .background(tokens.colors.outline)
                )
                Spacer(Modifier.width(6.dp))
                IconAction(Glyph.TRASH, tint = tokens.colors.danger) {
                    viewModel.deleteNote { id -> onDeleted(id) }
                }
            }

            Spacer(Modifier.height(8.dp))

            // Title.
            Box {
                if (titleValue.text.isEmpty()) {
                    BasicText("Title", style = AuraType.title.copy(color = tokens.colors.textSecondary))
                }
                BasicTextField(
                    value = titleValue,
                    onValueChange = {
                        titleValue = it
                        viewModel.onTitleChanged(it.text)
                    },
                    singleLine = true,
                    textStyle = AuraType.title.copy(color = tokens.colors.textPrimary),
                    cursorBrush = SolidColor(tokens.colors.accent),
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Assigned tags.
            if (noteTags.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                com.fadghost.notesapp.ui.components.FlowChips {
                    noteTags.forEach { TagChip(tag = it, selected = true) }
                }
            }

            Spacer(Modifier.height(12.dp))

            // Body — live-styled markdown with tappable checkboxes + smart lists.
            Box {
                val transformation = remember(tokens) {
                    MarkdownVisualTransformation(
                        textColor = tokens.colors.textPrimary,
                        markerColor = tokens.colors.textSecondary,
                        accent = tokens.colors.accent
                    )
                }
                BasicTextField(
                    value = bodyValue,
                    onValueChange = { new ->
                        val smart = MarkdownEdits.onNewline(bodyValue, new)
                        val applied = smart ?: new
                        val key = when {
                            smart != null -> UndoStack.CoalesceKey.FORMATTING
                            applied.text.length > bodyValue.text.length -> UndoStack.CoalesceKey.TYPING
                            applied.text.length < bodyValue.text.length -> UndoStack.CoalesceKey.DELETING
                            else -> UndoStack.CoalesceKey.FORMATTING
                        }
                        applyBody(applied, key)
                    },
                    onTextLayout = { bodyLayout = it },
                    textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
                    cursorBrush = SolidColor(tokens.colors.accent),
                    visualTransformation = transformation,
                    modifier = Modifier
                        .focusRequester(bodyFocus)
                        .fillMaxWidth()
                        .heightForBody()
                        // Swipe-right on a line indents it (PLAN.md §6).
                        .pointerInput(Unit) {
                            var accX = 0f
                            detectHorizontalDragGestures(
                                onDragStart = { accX = 0f },
                                onDragEnd = {
                                    if (accX > 90f) {
                                        applyBody(MarkdownEdits.indent(bodyValue), UndoStack.CoalesceKey.FORMATTING)
                                        haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                                    }
                                }
                            ) { _, dragAmount -> accX += dragAmount }
                        }
                )
                // Checkbox tap targets (overlay small hit-boxes on the marker only).
                CheckboxOverlay(
                    text = bodyValue.text,
                    layout = bodyLayout,
                    onToggle = { offset ->
                        MarkdownEdits.toggleCheckboxAt(bodyValue.text, offset)?.let { toggled ->
                            applyBody(
                                TextFieldValue(toggled, bodyValue.selection),
                                UndoStack.CoalesceKey.FORMATTING
                            )
                            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        }
                    }
                )
                // Circular audio chips at each transcript line start (PLAN.md §2.3).
                com.fadghost.notesapp.ui.voice.AudioChipOverlay(
                    attachments = audioChips,
                    layout = bodyLayout,
                    textLength = bodyValue.text.length,
                    onOpen = { openPlayer = it }
                )
            }
        }

        // Sticky formatting toolbar, docked above the keyboard.
        FormattingToolbar(
            canUndo = state.canUndo,
            canRedo = state.canRedo,
            onBold = { applyBody(MarkdownEdits.wrap(bodyValue, "**"), UndoStack.CoalesceKey.FORMATTING) },
            onItalic = { applyBody(MarkdownEdits.wrap(bodyValue, "*"), UndoStack.CoalesceKey.FORMATTING) },
            onHeading = { applyBody(MarkdownEdits.cycleHeading(bodyValue), UndoStack.CoalesceKey.FORMATTING) },
            onChecklist = { applyBody(MarkdownEdits.toggleChecklist(bodyValue), UndoStack.CoalesceKey.FORMATTING) },
            onBullet = { applyBody(MarkdownEdits.toggleBullet(bodyValue), UndoStack.CoalesceKey.FORMATTING) },
            onUndo = { viewModel.undo()?.let { bodyValue = TextFieldValue(it, TextRange(it.length)) } },
            onRedo = { viewModel.redo()?.let { bodyValue = TextFieldValue(it, TextRange(it.length)) } },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(horizontal = 12.dp)
                .navigationBarsPadding()
                .imePadding()
                .padding(bottom = 12.dp)
        )

        if (showPicker) {
            TagFolderPicker(
                allTags = allTags,
                assignedTagIds = noteTags.map { it.id }.toSet(),
                folders = folders,
                currentFolderId = state.folderId,
                onToggleTag = viewModel::toggleTag,
                onCreateTag = viewModel::createAndAssignTag,
                onSelectFolder = viewModel::moveToFolder,
                onCreateFolder = viewModel::createFolderAndMove,
                onDismiss = { showPicker = false }
            )
        }

        // Queued Clean-up that finished while offline / editor closed (PLAN.md §5).
        queuedResult?.let { cleaned ->
            if (!cleanupState.active) {
                QueuedResultBanner(
                    onApply = {
                        applyBody(TextFieldValue(cleaned, TextRange(cleaned.length)), UndoStack.CoalesceKey.BOUNDARY)
                        aiViewModel.applyQueuedResult(state.noteId)
                    },
                    onDismiss = { aiViewModel.dismissQueuedResult(state.noteId) },
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(top = 56.dp)
                )
            }
        }

        // First-open coach tip labelling the three AI icons (P1-3).
        EditorCoachTip(
            visible = coachVisible,
            onDismiss = {
                coachVisible = false
                coachScope.launch { coachStore.markSeen() }
            },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 52.dp)
        )

        // ✨ Clean-up before/after sheet.
        com.fadghost.notesapp.ui.ai.CleanupSheet(
            state = cleanupState,
            onSegment = aiViewModel::setSegment,
            onCancel = aiViewModel::cancelCleanup,
            onRegenerate = aiViewModel::regenerateCleanup,
            onKeepOriginal = aiViewModel::dismissCleanup,
            onAccept = {
                aiViewModel.acceptCleanup()?.let {
                    applyBody(TextFieldValue(it, TextRange(it.length)), UndoStack.CoalesceKey.BOUNDARY)
                }
            }
        )

        // 📅 Extract confirmation cards sheet.
        com.fadghost.notesapp.ui.ai.ExtractSheet(
            state = extractState,
            onAccept = aiViewModel::acceptCard,
            onReject = aiViewModel::rejectCard,
            onBeginEdit = aiViewModel::beginEdit,
            onCancelEdit = aiViewModel::cancelEdit,
            onApplyEdit = aiViewModel::applyEdit,
            onRevise = aiViewModel::reviseCard,
            onAcceptAll = aiViewModel::acceptAll,
            onDismiss = aiViewModel::dismissExtract
        )

        // Batch "Undo all" snackbar for accepted actions.
        com.fadghost.notesapp.ui.components.AuraUndoSnackbar(
            message = aiSnackbar,
            onAction = aiViewModel::undoAll,
            onDismiss = aiViewModel::dismissSnackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        )

        // No-key popover with a deep-link to Settings (PLAN.md §5 — never dead buttons).
        com.fadghost.notesapp.ui.ai.NoKeyPopover(
            visible = showNoKey,
            onOpenSettings = { showNoKey = false; focus.clearFocus(); viewModel.close(); onOpenAiSettings() },
            onDismiss = { showNoKey = false }
        )

        // Voice ramble → append transcript at caret (PLAN.md §5).
        com.fadghost.notesapp.ui.voice.VoiceRecordingSheet(
            visible = showVoiceSheet,
            targetNoteId = voiceTargetNoteId,
            appendMode = true,
            onDismiss = { showVoiceSheet = false },
            onTranscriptReady = { transcript -> insertTranscript(transcript) },
            viewModel = voiceViewModel
        )

        // Tap an audio chip → Aura popover player (PLAN.md §2.3).
        com.fadghost.notesapp.ui.voice.AudioPlayerPopover(
            attachment = openPlayer,
            noteBytes = openPlayer?.let { audioViewModel.noteBytes(it.noteId) } ?: 0L,
            onDelete = { id -> audioViewModel.deleteAttachment(id); openPlayer = null },
            onDismiss = { openPlayer = null }
        )
    }
}

@Composable
private fun MicAction(tint: androidx.compose.ui.graphics.Color, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        // Small mic glyph drawn inline (no Material) — capsule + stand.
        androidx.compose.foundation.Canvas(Modifier.size(22.dp)) {
            val s = size.minDimension
            val st = androidx.compose.ui.graphics.drawscope.Stroke(
                width = s * 0.08f,
                cap = androidx.compose.ui.graphics.StrokeCap.Round
            )
            drawRoundRect(
                color = tint,
                topLeft = androidx.compose.ui.geometry.Offset(s * 0.38f, s * 0.22f),
                size = androidx.compose.ui.geometry.Size(s * 0.24f, s * 0.36f),
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * 0.12f, s * 0.12f),
                style = st
            )
            drawLine(tint, androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.62f), androidx.compose.ui.geometry.Offset(s * 0.5f, s * 0.78f), st.width, st.cap)
            drawLine(tint, androidx.compose.ui.geometry.Offset(s * 0.36f, s * 0.78f), androidx.compose.ui.geometry.Offset(s * 0.64f, s * 0.78f), st.width, st.cap)
        }
    }
}

@Composable
private fun QueuedResultBanner(
    onApply: () -> Unit,
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
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText("AI cleanup ready", style = AuraType.body.copy(color = tokens.colors.textPrimary))
            BasicText("Ran while you were offline", style = AuraType.label.copy(color = tokens.colors.textSecondary))
        }
        val applyInteraction = remember { MutableInteractionSource() }
        BasicText(
            "Apply",
            style = AuraType.label.copy(color = tokens.colors.accent),
            modifier = Modifier
                .clip(RoundedCornerShape(tokens.radii.pill))
                .auraPress(applyInteraction)
                .clickable(
                    interactionSource = applyInteraction,
                    indication = null,
                    onClick = onApply
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
        val dismissInteraction = remember { MutableInteractionSource() }
        BasicText(
            "Dismiss",
            style = AuraType.label.copy(color = tokens.colors.textSecondary),
            modifier = Modifier
                .clip(RoundedCornerShape(tokens.radii.pill))
                .auraPress(dismissInteraction)
                .clickable(
                    interactionSource = dismissInteraction,
                    indication = null,
                    onClick = onDismiss
                )
                .padding(horizontal = 12.dp, vertical = 6.dp)
        )
    }
}

/** Give the body a comfortable minimum tap area while still growing with content. */
private fun Modifier.heightForBody(): Modifier =
    this.defaultMinSize(minHeight = 320.dp)

@Composable
private fun CheckboxOverlay(
    text: String,
    layout: TextLayoutResult?,
    onToggle: (Int) -> Unit
) {
    val layoutResult = layout ?: return
    val density = LocalDensity.current
    val rects = remember(text, layoutResult) { checkboxRects(text, layoutResult) }
    rects.forEach { (offset, rect) ->
        with(density) {
            // P2-1: expand the marker hit-box to a centred 48dp target.
            val target = 48.dp
            val cx = rect.left + rect.width / 2f
            val cy = rect.top + rect.height / 2f
            Box(
                Modifier
                    .offset(x = cx.toDp() - target / 2, y = cy.toDp() - target / 2)
                    .size(target)
                    .pointerInput(offset) {
                        detectTapGestures { onToggle(offset) }
                    }
            )
        }
    }
}

/** Bounding rects of the `[ ]` / `[x]` markers, one per checklist line. */
private fun checkboxRects(text: String, layout: TextLayoutResult): List<Pair<Int, Rect>> {
    val out = ArrayList<Pair<Int, Rect>>()
    var lineStart = 0
    val checklist = Regex("""^\s*[-*+] \[[ xX]] """)
    for (line in text.split("\n")) {
        val m = checklist.find(line)
        if (m != null) {
            val open = line.indexOf('[')
            val close = line.indexOf(']')
            if (open >= 0 && close > open) {
                val a = (lineStart + open).coerceIn(0, text.length)
                val b = (lineStart + close).coerceIn(0, text.length)
                runCatching {
                    val ra = layout.getBoundingBox(a)
                    val rb = layout.getBoundingBox(b)
                    out += (lineStart) to Rect(ra.left, ra.top, rb.right, rb.bottom)
                }
            }
        }
        lineStart += line.length + 1
    }
    return out
}

@Composable
private fun IconAction(
    glyph: Glyph,
    tint: androidx.compose.ui.graphics.Color? = null,
    onClick: () -> Unit
) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp) // P2-1: comfortable 48dp touch target.
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(glyph, tint ?: tokens.colors.textPrimary, Modifier.size(22.dp))
    }
}

@Composable
private fun PillAction(glyph: Glyph, label: String, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .defaultMinSize(minHeight = 48.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction, tint = true)
            .background(tokens.colors.surface)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AuraGlyph(glyph, tokens.colors.textSecondary, Modifier.size(16.dp))
        Spacer(Modifier.width(6.dp))
        BasicText(label, style = AuraType.label.copy(color = tokens.colors.textPrimary))
    }
}

/** First-open coach card labelling the three AI icons — Clean-up / Extract / Voice. */
@Composable
private fun EditorCoachTip(
    visible: Boolean,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    if (!visible) return
    val tokens = Aura.tokens
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.surfaceTranslucent)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
            .padding(horizontal = 16.dp, vertical = 14.dp)
    ) {
        BasicText("QUICK TOUR", style = AuraType.labelSm.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.height(10.dp))
        CoachRow(Glyph.SPARKLE, "Clean-up", "Tidy grammar & formatting")
        Spacer(Modifier.height(8.dp))
        CoachRow(Glyph.CALENDAR, "Extract", "Pull out reminders & dates")
        Spacer(Modifier.height(8.dp))
        CoachRow(Glyph.MIC, "Voice", "Ramble; we transcribe it")
        Spacer(Modifier.height(12.dp))
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .align(Alignment.End)
                .clip(RoundedCornerShape(tokens.radii.pill))
                .auraPress(interaction, tint = true)
                .background(tokens.colors.accent)
                .clickable(
                    interactionSource = interaction,
                    indication = null,
                    onClick = onDismiss
                )
                .padding(horizontal = 18.dp, vertical = 8.dp)
        ) {
            BasicText("Got it", style = AuraType.label.copy(color = tokens.colors.background))
        }
    }
}

@Composable
private fun CoachRow(glyph: Glyph, title: String, subtitle: String) {
    val tokens = Aura.tokens
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .size(32.dp)
                .clip(RoundedCornerShape(tokens.radii.pill))
                .background(tokens.colors.accent.copy(alpha = 0.16f)),
            contentAlignment = Alignment.Center
        ) { AuraGlyph(glyph, tokens.colors.accent, Modifier.size(18.dp)) }
        Spacer(Modifier.width(12.dp))
        Column {
            BasicText(title, style = AuraType.body.copy(color = tokens.colors.textPrimary))
            BasicText(subtitle, style = AuraType.label.copy(color = tokens.colors.textSecondary))
        }
    }
}
