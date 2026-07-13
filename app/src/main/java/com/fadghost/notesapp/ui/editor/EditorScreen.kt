package com.fadghost.notesapp.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draganddrop.toAndroidDragEvent
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
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
    voiceViewModel: com.fadghost.notesapp.ui.voice.VoiceRecordViewModel = hiltViewModel(),
    attachViewModel: com.fadghost.notesapp.ui.attach.EditorAttachmentViewModel = hiltViewModel()
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
    val memoryEnabled by aiViewModel.memoryEnabled.collectAsStateWithLifecycle()
    val cleanupState by aiViewModel.cleanup.collectAsStateWithLifecycle()
    val extractState by aiViewModel.extract.collectAsStateWithLifecycle()
    val memoryState by aiViewModel.memory.collectAsStateWithLifecycle()
    val aiSnackbar by aiViewModel.snackbar.collectAsStateWithLifecycle()
    val autoCleanTranscript by aiViewModel.autoCleanTranscript.collectAsStateWithLifecycle()
    val queuedResult by remember(state.noteId) { aiViewModel.pendingQueuedCleanup(state.noteId) }.collectAsStateWithLifecycle()

    val audioChips by audioViewModel.chips.collectAsStateWithLifecycle()
    var showVoiceSheet by remember { mutableStateOf(false) }
    var voiceTargetNoteId by remember { mutableStateOf(0L) }
    var openPlayer by remember { mutableStateOf<com.fadghost.notesapp.data.db.entity.AudioAttachment?>(null) }

    // Attachments (M-A): chips in the body, an ingest menu, tap popover, undoable remove.
    val attachmentsById by attachViewModel.byId.collectAsStateWithLifecycle()
    var showAttachMenu by remember { mutableStateOf(false) }
    var openAttachment by remember { mutableStateOf<com.fadghost.notesapp.data.db.entity.Attachment?>(null) }
    var expandAttachment by remember { mutableStateOf<com.fadghost.notesapp.data.db.entity.Attachment?>(null) }
    var annotateAttachment by remember { mutableStateOf<com.fadghost.notesapp.data.db.entity.Attachment?>(null) }
    var removedAttachment by remember { mutableStateOf<com.fadghost.notesapp.data.attach.RemovedAttachment?>(null) }
    var bodyBeforeRemove by remember { mutableStateOf<TextFieldValue?>(null) }

    LaunchedEffect(noteId) { viewModel.open(noteId, restoreDraft) }
    LaunchedEffect(state.noteId) { audioViewModel.bind(state.noteId) }
    LaunchedEffect(state.noteId) { attachViewModel.bind(state.noteId) }

    var titleValue by remember { mutableStateOf(TextFieldValue("")) }
    var bodyValue by remember { mutableStateOf(TextFieldValue("")) }
    var initializedFor by remember { mutableStateOf(-1L) }
    // One measured trigger opens the merged, tags-forward assignment panel.
    var showOrganize by remember { mutableStateOf(false) }
    var organizeAnchor by remember { mutableStateOf(Rect.Zero) }
    var showNoKey by remember { mutableStateOf(false) }
    // The AI sparkle menu (§1.9): one button opens the 4-row panel anchored to its bounds.
    var showAiMenu by remember { mutableStateOf(false) }
    var aiMenuAnchor by remember { mutableStateOf(Rect.Zero) }
    var bodyLayout by remember { mutableStateOf<TextLayoutResult?>(null) }
    // Y (root px) of the bottom of the title/header, so the first-run coach card can anchor
    // strictly BELOW it and never cover the title or top bar (bug 6).
    var coachAnchorY by remember { mutableStateOf(0) }

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

    // Rewrite (P4) — full restructure into the before/after sheet.
    fun onRewrite() {
        if (hasKey) { focus.clearFocus(); aiViewModel.startRewrite(state.noteId, bodyValue.text) }
        else showNoKey = true
    }

    // Add to memory (P1) — extract durable entries, confirm cards before any write.
    fun onAddMemory() {
        if (hasKey) { focus.clearFocus(); aiViewModel.startAddToMemory(state.noteId, bodyValue.text) }
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

    // Insert the inline attachment token at the caret (M-A). A leading space is added
    // when the caret butts against a word so the chip reads as its own inline element.
    fun insertAttachment(attId: Long) {
        val existing = bodyValue.text
        val caret = bodyValue.selection.end.coerceIn(0, existing.length)
        val needLead = caret > 0 && !existing[caret - 1].isWhitespace()
        val insert = (if (needLead) " " else "") + "[[att:$attId]] "
        val newText = existing.substring(0, caret) + insert + existing.substring(caret)
        applyBody(TextFieldValue(newText, TextRange(caret + insert.length)), UndoStack.CoalesceKey.BOUNDARY)
    }

    // System photo picker (no permission) + generic document picker. The note is forced
    // to exist only once files are actually chosen, so cancelling leaves no empty note.
    val photoPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.PickMultipleVisualMedia()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.ensureSaved { id ->
            uris.forEach { uri -> attachViewModel.ingest(id, uri) { att -> insertAttachment(att.id) } }
        }
    }
    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) viewModel.ensureSaved { id ->
            uris.forEach { uri -> attachViewModel.ingest(id, uri) { att -> insertAttachment(att.id) } }
        }
    }

    fun onAttach() { focus.clearFocus(); showAttachMenu = true }

    fun onPasteImage() {
        val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
        val clip = cm?.primaryClip ?: return
        val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
        if (uris.isNotEmpty()) viewModel.ensureSaved { id ->
            uris.forEach { uri -> attachViewModel.ingest(id, uri) { att -> insertAttachment(att.id) } }
        }
    }

    // Remove an attachment (M-A): strip its [[att:id]] token from the body and delete
    // the row+file, retaining both for an undoable restore (same id -> token re-resolves).
    fun removeAttachment(att: com.fadghost.notesapp.data.db.entity.Attachment) {
        openAttachment = null
        val before = bodyValue
        val token = "[[att:${att.id}]]"
        val idx = bodyValue.text.indexOf(token)
        if (idx >= 0) {
            var start = idx
            var end = idx + token.length
            if (end < bodyValue.text.length && bodyValue.text[end] == ' ') end++
            else if (start > 0 && bodyValue.text[start - 1] == ' ') start--
            val newText = bodyValue.text.removeRange(start, end)
            applyBody(
                TextFieldValue(newText, TextRange(start.coerceIn(0, newText.length))),
                UndoStack.CoalesceKey.BOUNDARY
            )
        }
        bodyBeforeRemove = before
        attachViewModel.remove(att.id) { removed -> removedAttachment = removed }
    }

    fun undoRemoveAttachment() {
        bodyBeforeRemove?.let { applyBody(it, UndoStack.CoalesceKey.BOUNDARY) }
        removedAttachment?.let { attachViewModel.restore(it) }
        removedAttachment = null
        bodyBeforeRemove = null
    }

    // Drag-and-drop into the editor (M-A): accept dropped image/file content URIs.
    val dndTarget = remember {
        object : DragAndDropTarget {
            override fun onDrop(event: DragAndDropEvent): Boolean {
                val drag = event.toAndroidDragEvent()
                val activity = context as? android.app.Activity
                runCatching { activity?.requestDragAndDropPermissions(drag) }
                val clip = drag.clipData ?: return false
                val uris = (0 until clip.itemCount).mapNotNull { clip.getItemAt(it).uri }
                if (uris.isEmpty()) return false
                viewModel.ensureSaved { id ->
                    uris.forEach { uri -> attachViewModel.ingest(id, uri) { att -> insertAttachment(att.id) } }
                }
                return true
            }
        }
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(tokens.colors.background)
            .dragAndDropTarget(
                shouldStartDragAndDrop = { event ->
                    val d = event.toAndroidDragEvent().clipDescription ?: return@dragAndDropTarget false
                    d.hasMimeType("image/*") || d.hasMimeType("application/*") ||
                        d.hasMimeType("text/uri-list") || d.hasMimeType("*/*")
                },
                target = dndTarget
            )
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 96.dp)
        ) {
            // Top bar. Back and trash stay pinned; the middle action cluster scrolls on
            // narrow screens, and one 48dp Organize trigger owns folder + tag assignment.
            BoxWithConstraints(
                Modifier.fillMaxWidth().padding(vertical = 8.dp)
            ) {
                val scrollable = maxWidth < 380.dp
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconAction(Glyph.BACK) {
                        focus.clearFocus()
                        viewModel.close()
                        onExit()
                    }
                    val clusterMod = if (scrollable) {
                        Modifier.weight(1f).horizontalScroll(rememberScrollState())
                    } else {
                        Modifier.weight(1f)
                    }
                    Row(clusterMod, verticalAlignment = Alignment.CenterVertically) {
                        // Single AI sparkle → the 4-row anchored panel (V3-PROMPTS §1.9).
                        Box(Modifier.onGloballyPositioned { aiMenuAnchor = it.boundsInRoot() }) {
                            IconAction(Glyph.SPARKLE, tint = tokens.colors.accent) {
                                focus.clearFocus(); showAiMenu = true
                            }
                        }
                        MicAction(tint = tokens.colors.accent) { onVoice() }
                        IconAction(Glyph.PAPERCLIP, tint = tokens.colors.accent) { onAttach() }
                        // Push the chips to the right of the cluster when there's room; use a
                        // fixed gap in the scrollable case (weight is invalid inside scroll).
                        if (scrollable) Spacer(Modifier.width(12.dp)) else Spacer(Modifier.weight(1f))
                        Box(Modifier.onGloballyPositioned { organizeAnchor = it.boundsInRoot() }) {
                            OrganizeAction(active = state.folderId != null || noteTags.isNotEmpty()) {
                                focus.clearFocus()
                                showOrganize = true
                            }
                        }
                    }
                    // Keep the destructive trash well clear of the Tags chip (P0-2): a hairline
                    // divider so it can't be fat-fingered for "Tags".
                    Spacer(Modifier.width(6.dp))
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
            }

            Spacer(Modifier.height(8.dp))

            // Title.
            Box(
                Modifier.onGloballyPositioned {
                    coachAnchorY = (it.positionInRoot().y + it.size.height).toInt()
                }
            ) {
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

            // Body — live-styled markdown, tappable checkboxes, smart lists, and inline
            // attachment bubble chips (M-A). The chip transform replaces [[att:id]] with
            // the file name, so overlays map source offsets through displayBody.mapOffset.
            Box {
                val displayBody = remember(bodyValue.text, attachmentsById, tokens) {
                    com.fadghost.notesapp.ui.attach.AttachmentBodyBuilder.build(
                        source = bodyValue.text,
                        attachments = attachmentsById,
                        textColor = tokens.colors.textPrimary,
                        markerColor = tokens.colors.textSecondary,
                        accent = tokens.colors.accent,
                        linkBlue = tokens.colors.linkBlue,
                        chipSurface = tokens.colors.textPrimary.copy(alpha = 0.06f),
                        missing = tokens.colors.danger
                    )
                }
                val transformation = remember(displayBody) {
                    androidx.compose.ui.text.input.VisualTransformation { displayBody.transformed }
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
                    mapOffset = displayBody::mapOffset,
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
                    mapOffset = displayBody::mapOffset,
                    onOpen = { openPlayer = it }
                )
                // Inline attachment bubble chips (M-A): glyph + tap → popover.
                com.fadghost.notesapp.ui.attach.AttachmentChipOverlay(
                    chips = displayBody.chips,
                    layout = bodyLayout,
                    onOpen = { id -> openAttachment = attachmentsById[id] }
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

        if (showOrganize) {
            OrganizePanel(
                anchorBounds = organizeAnchor,
                folders = folders,
                currentFolderId = state.folderId,
                allTags = allTags,
                assignedTagIds = noteTags.map { it.id }.toSet(),
                onSelectFolder = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.moveToFolder(it)
                },
                onCreateFolder = {
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                    viewModel.createFolderAndMove(it)
                },
                onToggleTag = {
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.toggleTag(it)
                },
                onCreateTag = { name, color ->
                    haptics.performHapticFeedback(HapticFeedbackType.TextHandleMove)
                    viewModel.createAndAssignTag(name, color)
                },
                onDismiss = { showOrganize = false }
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

        // First-open coach tip labelling the three AI icons (P1-3). Anchored strictly below
        // the title (bug 6) and dismissible by tapping outside as well as "Got it".
        EditorCoachTip(
            visible = coachVisible,
            anchorYpx = coachAnchorY,
            onDismiss = {
                coachVisible = false
                coachScope.launch { coachStore.markSeen() }
            }
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

        // ✨ AI sparkle menu — Clean up / Rewrite / Extract / Add to memory (§1.9).
        com.fadghost.notesapp.ui.ai.EditorAiMenu(
            visible = showAiMenu,
            anchor = aiMenuAnchor,
            memoryEnabled = memoryEnabled,
            onCleanup = { showAiMenu = false; onCleanup() },
            onRewrite = { showAiMenu = false; onRewrite() },
            onExtract = { showAiMenu = false; onExtract() },
            onAddMemory = { showAiMenu = false; onAddMemory() },
            onDismiss = { showAiMenu = false }
        )

        // 📖 Add-to-memory confirm cards (P1) — nothing is written until "Keep".
        com.fadghost.notesapp.ui.ai.MemorySheet(
            state = memoryState,
            onToggle = aiViewModel::toggleMemoryCard,
            onRemove = aiViewModel::removeMemoryCard,
            onBeginEdit = aiViewModel::beginMemoryEdit,
            onCancelEdit = aiViewModel::cancelMemoryEdit,
            onApplyEdit = aiViewModel::applyMemoryEdit,
            onKeep = aiViewModel::keepMemory,
            onDismiss = aiViewModel::dismissMemory
        )

        // Universal Undo snackbar — serves both extract batch-accept and memory saves.
        com.fadghost.notesapp.ui.components.AuraUndoSnackbar(
            message = aiSnackbar,
            onAction = aiViewModel::performUndo,
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

        // Attachment source menu (M-A ingest): Photo / File / Paste image.
        com.fadghost.notesapp.ui.attach.AttachMenu(
            visible = showAttachMenu,
            canPaste = remember(showAttachMenu) { showAttachMenu && clipboardHasImage(context) },
            onPhoto = {
                showAttachMenu = false
                photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
            },
            onFile = { showAttachMenu = false; filePicker.launch(arrayOf("*/*")) },
            onPasteImage = { showAttachMenu = false; onPasteImage() },
            onDismiss = { showAttachMenu = false }
        )

        // Tap a chip → attachment popover (M-A part 4): preview, name, size, actions.
        com.fadghost.notesapp.ui.attach.AttachmentPopover(
            attachment = openAttachment,
            onExpand = { att -> openAttachment = null; expandAttachment = att },
            onAnnotate = { att -> openAttachment = null; annotateAttachment = att },
            onShare = { att -> com.fadghost.notesapp.ui.attach.AttachmentActions.share(context, att) },
            onOpenExternally = { att -> com.fadghost.notesapp.ui.attach.AttachmentActions.openExternally(context, att) },
            onRemove = { att -> removeAttachment(att) },
            onDismiss = { openAttachment = null }
        )

        // Fullscreen image viewer (M-A part 5).
        com.fadghost.notesapp.ui.attach.AttachmentViewer(
            attachment = expandAttachment,
            onDismiss = { expandAttachment = null }
        )

        // Undo snackbar for an attachment removal (M-A part 4).
        com.fadghost.notesapp.ui.components.AuraUndoSnackbar(
            message = removedAttachment?.let {
                com.fadghost.notesapp.ui.components.UndoMessage("Attachment removed")
            },
            onAction = { undoRemoveAttachment() },
            onDismiss = { removedAttachment = null; bodyBeforeRemove = null },
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        )
    }

    // Annotate editor (M-A part 6): a full screen over the editor.
    annotateAttachment?.let { att ->
        com.fadghost.notesapp.ui.attach.AnnotateScreen(
            attachment = att,
            onCancel = { annotateAttachment = null },
            onSave = { bytes ->
                attachViewModel.saveAnnotation(state.noteId, att, bytes) { newId ->
                    // Repoint the note token at the annotated copy (original preserved).
                    val old = "[[att:${att.id}]]"
                    val new = "[[att:$newId]]"
                    if (bodyValue.text.contains(old)) {
                        applyBody(
                            TextFieldValue(bodyValue.text.replace(old, new), bodyValue.selection),
                            UndoStack.CoalesceKey.BOUNDARY
                        )
                    }
                    annotateAttachment = null
                }
            }
        )
    }
}

/** True when the system clipboard currently holds an image (enables "Paste image"). */
private fun clipboardHasImage(context: android.content.Context): Boolean {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as? android.content.ClipboardManager
    val desc = cm?.primaryClipDescription ?: return false
    return desc.hasMimeType("image/*")
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
    mapOffset: (Int) -> Int = { it },
    onToggle: (Int) -> Unit
) {
    val layoutResult = layout ?: return
    val density = LocalDensity.current
    val rects = remember(text, layoutResult, mapOffset) { checkboxRects(text, layoutResult, mapOffset) }
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
private fun checkboxRects(
    text: String,
    layout: TextLayoutResult,
    mapOffset: (Int) -> Int = { it }
): List<Pair<Int, Rect>> {
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
                    // Map source offsets into the transformed layout (attachment chips
                    // change lengths ahead of a checkbox on the same/earlier lines).
                    val ra = layout.getBoundingBox(mapOffset(a))
                    val rb = layout.getBoundingBox(mapOffset(b))
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

/** One 48dp trigger for both notebook and tag assignment. */
@Composable
private fun OrganizeAction(active: Boolean, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    val tint = if (active) tokens.colors.accent else tokens.colors.textSecondary
    Box(
        Modifier
            .size(48.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .semantics { contentDescription = "Organize note" }
            .auraPress(interaction, tint = true)
            .background(if (active) tokens.colors.accent.copy(alpha = 0.14f) else tokens.colors.surface)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(Glyph.FOLDER, tint, Modifier.size(20.dp))
        Box(
            Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 9.dp, bottom = 9.dp)
                .size(8.dp)
                .clip(CircleShape)
                .background(tint)
        )
    }
}

/**
 * First-open coach card labelling the three AI icons — Clean-up / Extract / Voice.
 * A transparent full-screen layer catches outside taps to dismiss; the card itself is
 * offset to sit just below the measured title bottom ([anchorYpx]) so it never overlaps
 * the top bar or the Title field (bug 6).
 */
@Composable
private fun EditorCoachTip(
    visible: Boolean,
    anchorYpx: Int,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val tokens = Aura.tokens
    val density = LocalDensity.current
    Box(
        Modifier
            .fillMaxSize()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss
            )
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .offset { androidx.compose.ui.unit.IntOffset(0, anchorYpx + with(density) { 8.dp.roundToPx() }) }
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(tokens.radii.md))
                .background(tokens.colors.surfaceTranslucent)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
                // Swallow taps on the card so they don't fall through to dismiss.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
        BasicText("QUICK TOUR", style = AuraType.labelSm.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.height(10.dp))
        CoachRow(Glyph.SPARKLE, "AI", "Clean up, rewrite, extract, remember")
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
