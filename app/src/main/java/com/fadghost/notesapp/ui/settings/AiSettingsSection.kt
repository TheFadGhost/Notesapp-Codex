package com.fadghost.notesapp.ui.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Popup
import androidx.compose.ui.window.PopupProperties
import androidx.hilt.navigation.compose.hiltViewModel
import com.fadghost.notesapp.data.ai.AiPreferences
import com.fadghost.notesapp.data.ai.model.CachedModel
import com.fadghost.notesapp.ui.ai.SoftButton
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.auraFloatShadow
import com.fadghost.notesapp.ui.theme.auraSheetShadow
import com.fadghost.notesapp.ui.theme.auraTopHighlight

/**
 * Settings → AI section (PLAN.md §5): paste/test/clear key, text + STT model
 * pickers backed by the cached /models list with favourites/recents and a
 * free-text ID escape hatch, and the cost read-out. Custom Aura surfaces only.
 */
@Composable
fun AiSettingsSection(viewModel: AiSettingsViewModel = hiltViewModel()) {
    val tokens = Aura.tokens
    val hasKey by viewModel.hasKey.collectAsStateWithLifecycle()
    val textModel by viewModel.textModel.collectAsStateWithLifecycle()
    val sttModel by viewModel.sttModel.collectAsStateWithLifecycle()
    val models by viewModel.models.collectAsStateWithLifecycle()
    val sttModels by viewModel.sttModels.collectAsStateWithLifecycle()
    val favorites by viewModel.favorites.collectAsStateWithLifecycle()
    val recents by viewModel.recents.collectAsStateWithLifecycle()
    val monthTotal by viewModel.monthTotal.collectAsStateWithLifecycle()
    val lastCall by viewModel.lastCall.collectAsStateWithLifecycle()
    val status by viewModel.status.collectAsStateWithLifecycle()
    val sttTestStatus by viewModel.sttTestStatus.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val autoCleanTranscript by viewModel.autoCleanTranscript.collectAsStateWithLifecycle()

    val clipboard = LocalClipboardManager.current
    var keyInput by remember { mutableStateOf("") }
    var picker by remember { mutableStateOf<PickerTarget?>(null) }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .auraSheetShadow(RoundedCornerShape(tokens.radii.md))
            .clip(RoundedCornerShape(tokens.radii.md))
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
            .padding(16.dp)
    ) {
        BasicText("AI", style = AuraType.labelSm.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.height(12.dp))

        // --- Key ---
        BasicText("OpenRouter API key", style = AuraType.bodyLg.copy(color = tokens.colors.textPrimary))
        BasicText(
            if (hasKey) "Stored securely (Keystore-encrypted, never backed up)"
            else "Add later — AI stays optional",
            style = AuraType.label.copy(color = tokens.colors.textSecondary)
        )
        Spacer(Modifier.height(10.dp))
        Box(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(tokens.radii.sm))
                .background(tokens.colors.background)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
                .padding(12.dp)
        ) {
            if (keyInput.isEmpty()) {
                BasicText(
                    if (hasKey) "••••••••  (enter to replace)" else "sk-or-…",
                    style = AuraType.body.copy(color = tokens.colors.textSecondary)
                )
            }
            BasicTextField(
                value = keyInput,
                onValueChange = { keyInput = it },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
                cursorBrush = SolidColor(tokens.colors.accent),
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SoftButton("Paste", filled = false, onClick = {
                clipboard.getText()?.text?.let { keyInput = it.trim() }
            })
            SoftButton("Save", filled = true, onClick = { viewModel.saveKey(keyInput); keyInput = "" })
            SoftButton("Test", filled = false, onClick = {
                viewModel.testConnection(keyInput.ifBlank { null })
            })
            if (hasKey) SoftButton("Clear", filled = false, onClick = { viewModel.clearKey() })
        }
        status?.let {
            Spacer(Modifier.height(8.dp))
            BasicText(it, style = AuraType.label.copy(color = tokens.colors.textPrimary))
        }

        DividerLineAi()

        // --- Models ---
        ModelRow("Text model", textModel) { picker = PickerTarget.TEXT }
        DividerLineAi()
        ModelRow(
            "Speech-to-text model",
            sttModel,
            subtitle = "Used for voice notes",
            onTest = { viewModel.testSttModel(sttModel) },
            testBusy = busy
        ) { picker = PickerTarget.STT }
        sttTestStatus?.let {
            BasicText(it, style = AuraType.label.copy(color = tokens.colors.textPrimary))
            Spacer(Modifier.height(4.dp))
        }

        DividerLineAi()

        // --- Voice transcript post-processing (PLAN.md §5) ---
        BasicText("Voice transcripts", style = AuraType.body.copy(color = tokens.colors.textPrimary))
        BasicText(
            if (autoCleanTranscript) "Auto clean-up runs after each transcription"
            else "Kept verbatim — no AI post-processing",
            style = AuraType.label.copy(color = tokens.colors.textSecondary)
        )
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            com.fadghost.notesapp.ui.components.PlainChip(
                label = "Keep verbatim",
                selected = !autoCleanTranscript,
                onClick = { viewModel.setAutoCleanTranscript(false) }
            )
            com.fadghost.notesapp.ui.components.PlainChip(
                label = "Auto clean-up",
                selected = autoCleanTranscript,
                onClick = { viewModel.setAutoCleanTranscript(true) }
            )
        }

        DividerLineAi()

        // --- Cost ---
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                BasicText("This month", style = AuraType.body.copy(color = tokens.colors.textPrimary))
                BasicText(formatUsd(monthTotal), style = AuraType.label.copy(color = tokens.colors.textSecondary))
            }
            lastCall?.let {
                LastCallChip(feature = it.feature, cost = it.costUsd)
            }
        }
    }

    // Model picker sheet.
    val target = picker
    val sttMode = target == PickerTarget.STT
    ModelPickerSheet(
        visible = target != null,
        title = if (sttMode) "Speech-to-text model" else "Text model",
        current = if (sttMode) sttModel else textModel,
        // STT: curated trio + live /models?output_modalities=transcription result
        // (item 9), plus a custom-id free-text entry. Text: the cached /models list
        // with favourites/recents/free-text.
        models = if (sttMode) sttModels else models,
        favorites = favorites,
        recents = recents,
        busy = busy,
        // STT has no favourites/recents grouping — just the (live) list + custom entry.
        sttMode = sttMode,
        recommendedModels = (if (sttMode) {
            AiPreferences.RECOMMENDED_STT_MODELS
        } else {
            AiPreferences.RECOMMENDED_TEXT_MODELS
        }).map { (id, name) -> CachedModel(id = id, name = name) },
        onRefresh = if (sttMode) viewModel::refreshSttModels else viewModel::refreshModels,
        onToggleFavorite = viewModel::toggleFavorite,
        onSelect = { id ->
            if (sttMode) viewModel.setSttModel(id) else viewModel.setTextModel(id)
            picker = null
        },
        onDismiss = { picker = null }
    )
}

private enum class PickerTarget { TEXT, STT }

@Composable
private fun ModelRow(
    label: String,
    value: String,
    subtitle: String? = null,
    onTest: (() -> Unit)? = null,
    testBusy: Boolean = false,
    onClick: () -> Unit
) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radii.sm))
            .auraPress(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(label, style = AuraType.body.copy(color = tokens.colors.textPrimary))
            BasicText(value, style = AuraType.label.copy(color = tokens.colors.accent))
            subtitle?.let { BasicText(it, style = AuraType.label.copy(color = tokens.colors.textSecondary)) }
        }
        // Validation QoL: lets a dead/renamed STT model id be caught in Settings
        // instead of mid-recording (item 9). Nested inside the row's own clickable
        // area but hit-tests first, so it doesn't also open the picker.
        if (onTest != null) {
            SoftButton(if (testBusy) "…" else "Test", filled = false, onClick = onTest)
            Spacer(Modifier.size(8.dp))
        }
        AuraGlyph(Glyph.CHEVRON, tokens.colors.textSecondary, Modifier.size(18.dp))
    }
}

@Composable
private fun LastCallChip(feature: String, cost: Double) {
    val tokens = Aura.tokens
    Row(
        Modifier
            .clip(RoundedCornerShape(tokens.radii.pill))
            .background(tokens.colors.accent.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        BasicText("last $feature ${formatUsd(cost)}", style = AuraType.label.copy(color = tokens.colors.accent))
    }
}

@Composable
private fun ModelPickerSheet(
    visible: Boolean,
    title: String,
    current: String,
    models: List<CachedModel>,
    favorites: Set<String>,
    recents: List<String>,
    busy: Boolean,
    sttMode: Boolean = false,
    recommendedModels: List<CachedModel>,
    onRefresh: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    var freeText by remember(visible) { mutableStateOf("") }
    // Static recommendations remain visible even before the first refresh. When an id is
    // present in the live list, its fetched metadata is used in preference to the fallback.
    val fetchedById = models.associateBy { it.id }
    val recommendedIds = recommendedModels.mapTo(mutableSetOf()) { it.id }
    val topModels = recommendedModels.map { model -> fetchedById[model.id] ?: model }
    val otherModels = models.filterNot { it.id in recommendedIds }
    // Render the picker in a real overlay window (Popup) so it draws above the entire
    // screen with its own scrim. Previously this Box composed inline inside Settings'
    // vertical-scroll Column, where fillMaxSize collapsed and the sheet was laid out
    // off-screen below the page content — so tapping a model row appeared to do nothing.
    // MutableTransitionState keeps the Popup mounted through the exit animation, then
    // unmounts it so it never blocks touches on the settings screen behind it.
    val sheetState = remember { MutableTransitionState(false) }
    sheetState.targetState = visible
    if (sheetState.currentState || sheetState.targetState) Popup(
        alignment = Alignment.TopStart,
        onDismissRequest = onDismiss,
        properties = PopupProperties(focusable = true)
    ) {
        AnimatedVisibility(sheetState, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.fillMaxSize()) {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = tokens.elevation.scrim))
                    .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
            ) {
                AnimatedVisibility(
                    sheetState.targetState,
                    enter = slideInVertically(spring(stiffness = Spring.StiffnessLow)) { it } + fadeIn(),
                    exit = slideOutVertically { it } + fadeOut(),
                    modifier = Modifier.align(Alignment.BottomCenter)
                ) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .auraFloatShadow(RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
                        .clip(RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
                        .background(tokens.colors.surface)
                        .border(1.dp, tokens.colors.outline, RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
                        .auraTopHighlight(tokens.radii.lg)
                        .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                        .navigationBarsPadding()
                        .padding(20.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        BasicText(title, style = AuraType.titleLg.copy(color = tokens.colors.textPrimary))
                        Spacer(Modifier.weight(1f))
                        // STT: refreshes from /models?output_modalities=transcription (item 9).
                        SoftButton(if (busy) "…" else "Refresh", filled = false, onClick = onRefresh)
                    }
                    Spacer(Modifier.height(12.dp))

                    if (sttMode) {
                        BasicText(
                            "Curated + live transcription models OpenRouter supports. Cheapest first.",
                            style = AuraType.label.copy(color = tokens.colors.textSecondary)
                        )
                        Spacer(Modifier.height(12.dp))
                    }

                    // Free-text custom model id (item 9 — future-proofs against the curated/
                    // live list going stale, e.g. a renamed or newly-added STT model).
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(tokens.radii.sm))
                                .background(tokens.colors.background)
                                .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
                                .padding(12.dp)
                        ) {
                            if (freeText.isEmpty()) {
                                BasicText(
                                    if (sttMode) "custom model id…" else "enter any model id…",
                                    style = AuraType.body.copy(color = tokens.colors.textSecondary)
                                )
                            }
                            BasicTextField(
                                value = freeText,
                                onValueChange = { freeText = it },
                                singleLine = true,
                                textStyle = AuraType.body.copy(color = tokens.colors.textPrimary),
                                cursorBrush = SolidColor(tokens.colors.accent),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        Spacer(Modifier.size(8.dp))
                        SoftButton("Use", filled = true, onClick = { if (freeText.isNotBlank()) onSelect(freeText.trim()) })
                    }
                    Spacer(Modifier.height(12.dp))

                    Column(
                        Modifier.heightIn(max = 380.dp).verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        if (topModels.isNotEmpty()) {
                            GroupLabel("Recommended")
                            topModels.forEach {
                                ModelItem(
                                    it,
                                    it.id == current,
                                    favorite = favorites.contains(it.id),
                                    onSelect = onSelect,
                                    onToggleFavorite = if (sttMode) null else onToggleFavorite
                                )
                            }
                        }
                        if (sttMode) {
                            // STT intentionally has no favourites/recents grouping.
                            GroupLabel(if (otherModels.isEmpty()) "No other transcription models" else "Other transcription models")
                            otherModels.forEach { ModelItem(it, it.id == current, favorite = false, onSelect = onSelect, onToggleFavorite = null) }
                        } else {
                            val favModels = otherModels.filter { favorites.contains(it.id) }
                            val recentModels = recents.mapNotNull { r -> otherModels.firstOrNull { it.id == r } }
                            if (favModels.isNotEmpty()) {
                                GroupLabel("Favourites")
                                favModels.forEach { ModelItem(it, it.id == current, favorites.contains(it.id), onSelect, onToggleFavorite) }
                            }
                            if (recentModels.isNotEmpty()) {
                                GroupLabel("Recent")
                                recentModels.forEach { ModelItem(it, it.id == current, favorites.contains(it.id), onSelect, onToggleFavorite) }
                            }
                            GroupLabel(if (otherModels.isEmpty()) "No other models cached — tap Refresh" else "All models")
                            otherModels.forEach { ModelItem(it, it.id == current, favorites.contains(it.id), onSelect, onToggleFavorite) }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                    SoftButton("Done", filled = false, onClick = onDismiss)
                }
            }
        }
    }
    }
}

@Composable
private fun GroupLabel(text: String) {
    val tokens = Aura.tokens
    BasicText(text, style = AuraType.label.copy(color = tokens.colors.textSecondary), modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ModelItem(
    model: CachedModel,
    selected: Boolean,
    favorite: Boolean,
    onSelect: (String) -> Unit,
    onToggleFavorite: ((String) -> Unit)?
) {
    val tokens = Aura.tokens
    val rowInteraction = remember { MutableInteractionSource() }
    val pinInteraction = remember { MutableInteractionSource() }
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radii.sm))
            .auraPress(rowInteraction)
            .background(if (selected) tokens.colors.accent.copy(alpha = 0.14f) else tokens.colors.background)
            .border(1.dp, if (selected) tokens.colors.accent else tokens.colors.outline, RoundedCornerShape(tokens.radii.sm))
            .clickable(rowInteraction, indication = null, onClick = { onSelect(model.id) })
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            BasicText(model.name, style = AuraType.body.copy(color = tokens.colors.textPrimary))
            BasicText(model.id, style = AuraType.label.copy(color = tokens.colors.textSecondary))
        }
        // Favourite pin is text-model only; the STT trio omits it.
        if (onToggleFavorite != null) {
            Box(
                Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(tokens.radii.pill))
                    .auraPress(pinInteraction)
                    .clickable(pinInteraction, indication = null, onClick = { onToggleFavorite(model.id) }),
                contentAlignment = Alignment.Center
            ) {
                AuraGlyph(Glyph.PIN, if (favorite) tokens.colors.accent else tokens.colors.textSecondary.copy(alpha = 0.4f), Modifier.size(16.dp))
            }
        } else if (selected) {
            AuraGlyph(Glyph.CHECK, tokens.colors.accent, Modifier.size(18.dp))
        }
    }
}

@Composable
private fun DividerLineAi() {
    val tokens = Aura.tokens
    Spacer(Modifier.height(8.dp))
    Box(Modifier.fillMaxWidth().height(1.dp).background(tokens.colors.outline))
    Spacer(Modifier.height(8.dp))
}

private fun formatUsd(v: Double): String = "$" + String.format("%.4f", v)
