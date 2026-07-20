package com.fadghost.notesapp.ui.ask

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fadghost.notesapp.data.ask.AskRole
import com.fadghost.notesapp.data.ask.AskSource
import com.fadghost.notesapp.data.ask.AskSourceKind
import com.fadghost.notesapp.ui.ai.ExtractSheet
import com.fadghost.notesapp.ui.ai.MemorySheet
import com.fadghost.notesapp.ui.ai.SoftButton
import com.fadghost.notesapp.ui.components.AuraUndoSnackbar
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.shell.LocalNavPillClearance
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.auraFloatShadow
import com.fadghost.notesapp.ui.theme.MotionTokens
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState

@Composable
fun AskScreen(
    onOpenNote: (Long) -> Unit,
    onOpenAiSettings: () -> Unit = {},
    viewModel: AskViewModel = hiltViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val tokens = Aura.tokens
    val navClearance = LocalNavPillClearance.current
    val listState = rememberLazyListState()
    // Saveable: a half-typed question must survive rotation / process death.
    var input by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    // Clearing wipes the whole conversation — worth one confirm (no undo exists).
    var confirmClear by remember { mutableStateOf(false) }

    fun send() {
        val text = input.trim()
        if (text.isNotEmpty() && !state.working) {
            viewModel.send(text)
            input = ""
        }
    }

    // Nav re-tap scrolls to the top of the conversation — parity with every other tab.
    LaunchedEffect(Unit) {
        com.fadghost.notesapp.ui.shell.ShellSignals.flow.collect { msg ->
            if (msg.tab == com.fadghost.notesapp.ui.shell.NavTab.ASK &&
                msg.signal == com.fadghost.notesapp.ui.shell.ShellSignal.SCROLL_TOP
            ) {
                listState.animateScrollToItem(0)
            }
        }
    }

    val lastTextLength = state.messages.lastOrNull()?.text?.length ?: 0
    LaunchedEffect(state.messages.size, lastTextLength) {
        val lastIndex = state.messages.lastIndex
        if (lastIndex < 0) return@LaunchedEffect
        val visibleLast = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: lastIndex
        if (visibleLast >= lastIndex - 1) listState.scrollToItem(lastIndex)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(tokens.colors.background)
            .statusBarsPadding()
            .imePadding()
    ) {
        Column(Modifier.fillMaxSize()) {
            AskHeader(
                hasMessages = state.messages.isNotEmpty(),
                onClear = { confirmClear = true }
            )
            if (state.working) AskStatusPill()

            if (state.messages.isEmpty()) {
                AskEmptyState(
                    hasKey = hasKey,
                    onExample = { viewModel.send(it) },
                    onOpenAiSettings = onOpenAiSettings,
                    modifier = Modifier.weight(1f)
                )
            } else {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentPadding = PaddingValues(horizontal = 20.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(state.messages, key = { it.id }) { message ->
                        AskBubble(
                            modifier = Modifier.animateItem(),
                            message = message,
                            onSource = { source ->
                                if (source.kind == AskSourceKind.NOTE && source.noteId != null) {
                                    onOpenNote(source.noteId)
                                } else viewModel.selectSource(source)
                            }
                        )
                    }
                }
            }

            state.error?.let {
                AskError(message = it, onRetry = viewModel::retryLast)
            }

            AskComposer(
                value = input,
                onValueChange = { input = it },
                working = state.working,
                onSend = ::send,
                onCancel = viewModel::cancel,
                modifier = Modifier.padding(bottom = navClearance)
            )
        }

        state.selectedSource?.let { source ->
            MemorySourceOverlay(source = source, onDismiss = { viewModel.selectSource(null) })
        }

        // Clear-conversation confirm: one tap wiped the whole thread with no undo.
        if (confirmClear) {
            androidx.activity.compose.BackHandler { confirmClear = false }
            Box(
                Modifier
                    .fillMaxSize()
                    .background(tokens.colors.scrimTint.copy(alpha = tokens.elevation.scrim))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { confirmClear = false },
                contentAlignment = Alignment.Center
            ) {
                Column(
                    Modifier
                        .padding(horizontal = 36.dp)
                        .clip(RoundedCornerShape(tokens.radii.lg))
                        .background(tokens.colors.surface)
                        .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .padding(22.dp)
                ) {
                    BasicText(
                        "Clear this conversation?",
                        style = AuraType.titleSm.copy(color = tokens.colors.textPrimary)
                    )
                    Spacer(Modifier.height(6.dp))
                    BasicText(
                        "Folio forgets this chat. Your notes and memory are untouched.",
                        style = AuraType.body.copy(color = tokens.colors.textSecondary)
                    )
                    Spacer(Modifier.height(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoftButton("Keep", filled = true, onClick = { confirmClear = false })
                        SoftButton("Clear", filled = false, onClick = {
                            confirmClear = false
                            viewModel.clearConversation()
                        })
                    }
                }
            }
        }

        AuraUndoSnackbar(
            message = state.snackbar,
            onAction = viewModel::performUndo,
            onDismiss = viewModel::dismissSnackbar,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = navClearance + 82.dp)
        )

        ExtractSheet(
            state = state.extract,
            onAccept = viewModel::acceptAction,
            onReject = viewModel::rejectAction,
            onBeginEdit = viewModel::beginActionEdit,
            onCancelEdit = viewModel::cancelActionEdit,
            onApplyEdit = viewModel::applyActionEdit,
            onRevise = viewModel::reviseAction,
            onAcceptAll = viewModel::acceptAllActions,
            onDismiss = viewModel::dismissExtract,
            onRetry = viewModel::retryExtract
        )

        MemorySheet(
            state = state.memory,
            onToggle = viewModel::toggleMemory,
            onRemove = viewModel::removeMemory,
            onBeginEdit = viewModel::beginMemoryEdit,
            onCancelEdit = viewModel::cancelMemoryEdit,
            onApplyEdit = viewModel::applyMemoryEdit,
            onKeep = viewModel::keepMemory,
            onDismiss = viewModel::dismissMemory,
            onRetry = viewModel::retryMemory
        )
    }
}

@Composable
private fun AskStatusPill() {
    val tokens = Aura.tokens
    Row(
        Modifier
            .padding(horizontal = 20.dp, vertical = 2.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .background(tokens.colors.accent.copy(alpha = 0.12f))
            .border(1.dp, tokens.colors.accent.copy(alpha = 0.28f), RoundedCornerShape(tokens.radii.pill))
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        AuraGlyph(Glyph.SPARKLE, tokens.colors.accent, Modifier.size(14.dp))
        BasicText("Folio is checking your pages", style = AuraType.label.copy(color = tokens.colors.textSecondary))
    }
}

@Composable
private fun AskHeader(hasMessages: Boolean, onClear: () -> Unit) {
    val tokens = Aura.tokens
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText("YOUR PAGES", style = AuraType.labelSm.copy(color = tokens.colors.textSecondary))
            Spacer(Modifier.height(2.dp))
            BasicText("Ask Folio", style = AuraType.titleLg.copy(color = tokens.colors.textPrimary))
        }
        if (hasMessages) {
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .auraPress(interaction, tint = true)
                    .background(tokens.colors.surface)
                    .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
                    .clickable(interactionSource = interaction, indication = null, onClick = onClear)
                    .semantics { contentDescription = "Clear conversation" },
                contentAlignment = Alignment.Center
            ) { AuraGlyph(Glyph.TRASH, tokens.colors.textSecondary, Modifier.size(19.dp)) }
        }
    }
}

@Composable
private fun AskEmptyState(
    hasKey: Boolean,
    onExample: (String) -> Unit,
    onOpenAiSettings: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    Column(
        modifier.fillMaxWidth().padding(horizontal = 28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            Modifier.size(64.dp).clip(RoundedCornerShape(tokens.radii.lg))
                .background(tokens.colors.accent.copy(alpha = 0.14f)),
            contentAlignment = Alignment.Center
        ) { AuraGlyph(Glyph.BOOK, tokens.colors.accent, Modifier.size(30.dp)) }
        Spacer(Modifier.height(18.dp))
        BasicText(
            "Ask about anything you've saved",
            style = AuraType.titleSm.copy(color = tokens.colors.textPrimary, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(7.dp))
        BasicText(
            "Folio is your AI librarian — it checks your notes and memory, then shows exactly where an answer came from.",
            style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(18.dp))
        if (!hasKey) {
            // Teach the key requirement BEFORE the first dead-end question (council blocker).
            BasicText(
                "Folio needs your OpenRouter key to answer — it takes a minute to add.",
                style = AuraType.label.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
            )
            Spacer(Modifier.height(10.dp))
            SoftButton("Open AI settings", filled = true, onClick = onOpenAiSettings)
            Spacer(Modifier.height(18.dp))
        }
        listOf(
            "What did I plan for this month?",
            "What have I saved about the gym?",
            "Summarise my recent project notes"
        ).forEach { example ->
            val interaction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(vertical = 4.dp)
                    .clip(RoundedCornerShape(tokens.radii.md))
                    .auraPress(interaction)
                    .background(tokens.colors.surface)
                    .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
                    .clickable(interactionSource = interaction, indication = null) { onExample(example) }
                    .padding(horizontal = 15.dp, vertical = 12.dp)
            ) { BasicText(example, style = AuraType.body.copy(color = tokens.colors.textPrimary)) }
        }
    }
}

@Composable
private fun AskBubble(message: AskMessage, onSource: (AskSource) -> Unit, modifier: Modifier = Modifier) {
    val tokens = Aura.tokens
    val user = message.role == AskRole.USER
    val bubbleShape = RoundedCornerShape(
        topStart = tokens.radii.lg,
        topEnd = tokens.radii.lg,
        bottomStart = if (user) tokens.radii.lg else 4.dp,
        bottomEnd = if (user) 4.dp else tokens.radii.lg
    )
    Column(
        modifier.fillMaxWidth(),
        horizontalAlignment = if (user) Alignment.End else Alignment.Start
    ) {
        Column(
            Modifier
                .widthIn(max = 350.dp)
                .clip(bubbleShape)
                .background(if (user) tokens.colors.accent.copy(alpha = 0.18f) else tokens.colors.surface)
                .border(
                    1.dp,
                    if (user) tokens.colors.accent.copy(alpha = 0.42f) else tokens.colors.outline,
                    bubbleShape
                )
                .padding(horizontal = 15.dp, vertical = 12.dp)
        ) {
            if (message.text.isNotBlank()) {
                BasicText(message.text, style = AuraType.body.copy(color = tokens.colors.textPrimary))
            }
            if (message.streaming) {
                if (message.text.isNotBlank()) Spacer(Modifier.height(7.dp))
                BasicText("Checking your notes\u2026", style = AuraType.label.copy(color = tokens.colors.textSecondary))
            }
        }
        if (message.sources.isNotEmpty()) {
            Spacer(Modifier.height(7.dp))
            Row(
                Modifier.widthIn(max = 350.dp).horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(7.dp)
            ) {
                message.sources.forEach { source -> SourceChip(source, onSource) }
            }
        }
    }
}

@Composable
private fun SourceChip(source: AskSource, onClick: (AskSource) -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .heightIn(min = 48.dp)
            .auraPress(interaction, tint = true)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .clickable(interactionSource = interaction, indication = null) { onClick(source) }
            .semantics { role = Role.Button; contentDescription = "Source: ${source.label}" }
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        AuraGlyph(
            if (source.kind == AskSourceKind.NOTE) Glyph.DOCUMENT else Glyph.BOOK,
            tokens.colors.accent,
            Modifier.size(14.dp)
        )
        Spacer(Modifier.size(5.dp))
        BasicText(source.label, style = AuraType.label.copy(color = tokens.colors.textPrimary), maxLines = 1)
    }
}

@Composable
private fun AskComposer(
    value: String,
    onValueChange: (String) -> Unit,
    working: Boolean,
    onSend: () -> Unit,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp)
            .clip(RoundedCornerShape(tokens.radii.lg))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
            .padding(start = 15.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                BasicText("Ask your notes\u2026", style = AuraType.body.copy(color = tokens.colors.textSecondary))
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                enabled = !working,
                textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(tokens.colors.accent),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.size(8.dp))
        val interaction = remember { MutableInteractionSource() }
        Box(
            Modifier
                .size(44.dp)
                .clip(RoundedCornerShape(tokens.radii.pill))
                .auraPress(interaction, tint = true)
                .background(if (working || value.isNotBlank()) tokens.colors.accent else tokens.colors.outline)
                .clickable(interactionSource = interaction, indication = null) {
                    if (working) onCancel() else onSend()
                }
                .semantics { contentDescription = if (working) "Stop answer" else "Send question" },
            contentAlignment = Alignment.Center
        ) {
            AuraGlyph(if (working) Glyph.CLOSE else Glyph.SEND, tokens.colors.background, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun AskError(message: String, onRetry: () -> Unit) {
    val tokens = Aura.tokens
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp)
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.danger.copy(alpha = 0.10f))
            .border(1.dp, tokens.colors.danger.copy(alpha = 0.45f), RoundedCornerShape(tokens.radii.md))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText(message, style = AuraType.label.copy(color = tokens.colors.textPrimary), modifier = Modifier.weight(1f))
        Spacer(Modifier.size(8.dp))
        SoftButton("Retry", filled = false, onClick = onRetry)
    }
}

@Composable
private fun MemorySourceOverlay(source: AskSource, onDismiss: () -> Unit) {
    val tokens = Aura.tokens
    androidx.activity.compose.BackHandler { onDismiss() }
    // Entrance fade (council: overlays teleported in with zero motion).
    var entranceIn by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { entranceIn = true }
    val entranceAlpha by animateFloatAsState(
        if (entranceIn) 1f else 0f,
        MotionTokens.fast(LocalReduceMotion.current), label = "overlayEntrance"
    )

    Box(
        Modifier.fillMaxSize().background(tokens.colors.scrimTint.copy(alpha = tokens.elevation.scrim))
            .graphicsLayer { alpha = entranceAlpha }
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center
    ) {
        Column(
            Modifier.padding(horizontal = 28.dp).widthIn(max = 420.dp)
                .auraFloatShadow(RoundedCornerShape(tokens.radii.lg))
                .clip(RoundedCornerShape(tokens.radii.lg))
                .background(tokens.colors.surface)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.lg))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                .padding(20.dp)
        ) {
            BasicText(
                source.label,
                style = AuraType.titleSm.copy(color = tokens.colors.textPrimary),
                maxLines = 2,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis
            )
            Spacer(Modifier.height(8.dp))
            BasicText(source.excerpt, style = AuraType.body.copy(color = tokens.colors.textSecondary))
            Spacer(Modifier.height(16.dp))
            SoftButton("Close", filled = false, onClick = onDismiss)
        }
    }
}
