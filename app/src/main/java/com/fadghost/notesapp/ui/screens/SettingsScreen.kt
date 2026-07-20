package com.fadghost.notesapp.ui.screens

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.text.BasicText
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.fadghost.notesapp.data.backup.ImportMode
import com.fadghost.notesapp.data.prefs.ThemeMode
import com.fadghost.notesapp.ui.components.SectionCard
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.settings.BackupUiState
import com.fadghost.notesapp.ui.settings.BackupViewModel
import com.fadghost.notesapp.ui.shell.LocalNavPillClearance
import com.fadghost.notesapp.ui.shell.NavTab
import com.fadghost.notesapp.ui.shell.ShellSignal
import com.fadghost.notesapp.ui.shell.ShellSignals
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.auraSheetShadow
import kotlinx.coroutines.launch

@Composable
fun SettingsScreen(
    currentMode: ThemeMode,
    onSelectMode: (ThemeMode) -> Unit
) {
    val tokens = Aura.tokens
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    var aiSectionY by remember { mutableStateOf(0) }
    // Nav re-tap scrolls Settings back to the top (V2-SPEC item 13); the AI deep link
    // scrolls straight to the AI card so "Open Settings" keeps its promise.
    LaunchedEffect(Unit) {
        ShellSignals.flow.collect { msg ->
            if (msg.tab != NavTab.SETTINGS) return@collect
            when (msg.signal) {
                ShellSignal.SCROLL_TOP -> scope.launch { scrollState.animateScrollTo(0) }
                ShellSignal.FOCUS_AI_SETTINGS ->
                    scope.launch { scrollState.animateScrollTo(aiSectionY) }
                else -> {}
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 12.dp, bottom = LocalNavPillClearance.current)
    ) {
        // Header: eyebrow + serif title, matching the other tabs.
        BasicText("PREFERENCES", style = AuraType.labelSm.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.height(2.dp))
        BasicHeader("Settings")
        Spacer(Modifier.height(24.dp))

        // Grouped for a daily driver (ux.md §3 P0): Look & feel / Features / Your data.
        GroupLabel("Look & feel")
        com.fadghost.notesapp.ui.settings.AppearanceSettingsSection()

        Spacer(Modifier.height(24.dp))
        GroupLabel("Features")
        com.fadghost.notesapp.ui.settings.DiarySettingsSection()
        Spacer(Modifier.height(16.dp))
        Box(Modifier.onGloballyPositioned { aiSectionY = it.positionInParent().y.toInt() }) {
            com.fadghost.notesapp.ui.settings.AiSettingsSection()
        }
        Spacer(Modifier.height(16.dp))
        com.fadghost.notesapp.ui.settings.AutomationSettingsSection()

        Spacer(Modifier.height(24.dp))
        GroupLabel("Your data")
        BackupSection()
        Spacer(Modifier.height(16.dp))
        com.fadghost.notesapp.ui.voice.VoiceStorageSection()
    }
}

/** Uppercase eyebrow that heads a settings group (visual.md §5.6). */
@Composable
private fun GroupLabel(text: String) {
    BasicText(
        text.uppercase(),
        style = AuraType.labelSm.copy(color = Aura.tokens.colors.textSecondary),
        modifier = Modifier.padding(start = 4.dp, bottom = 10.dp)
    )
}

@Composable
private fun BackupSection(viewModel: BackupViewModel = hiltViewModel()) {
    val tokens = Aura.tokens
    val status by viewModel.status.collectAsStateWithLifecycle()
    val pending by viewModel.pendingPreview.collectAsStateWithLifecycle()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val lastBackupAt by viewModel.lastBackupAt.collectAsStateWithLifecycle()

    val exporting = uiState is BackupUiState.Exporting
    val importing = uiState is BackupUiState.Importing
    val busy = exporting || importing
    val error = uiState as? BackupUiState.Error

    // Inline danger confirm for Replace (ux.md P0-1): tapping "Replace" expands this,
    // and only the danger button below actually wipes + restores.
    var confirmReplace by remember { mutableStateOf(false) }
    var showErrorDetails by remember { mutableStateOf(false) }
    // Collapse the confirm whenever the pending preview clears (import ran or cancelled).
    LaunchedEffect(pending) { if (pending == null) confirmReplace = false }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri -> uri?.let(viewModel::export) }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let(viewModel::loadPreview) }

    SectionCard(title = "Backup") {
        // Last-backup nudge (IDEAS #83): honest recency + amber when it goes stale.
        val now = remember { System.currentTimeMillis() }
        val stale = com.fadghost.notesapp.data.prefs.BackupPreferences.isStale(lastBackupAt, now)
        BasicText(
            com.fadghost.notesapp.data.prefs.BackupPreferences.describe(lastBackupAt, now) +
                if (stale) " — a fresh export keeps your notes safe." else "",
            style = AuraType.label.copy(
                color = if (stale) tokens.colors.accent else tokens.colors.textSecondary
            ),
            modifier = Modifier.padding(bottom = 8.dp)
        )
        ActionRow(
            title = "Export all notes",
            subtitle = "ZIP: markdown + metadata + checksums",
            busyLabel = if (exporting) "Exporting…" else null,
            enabled = !busy
        ) {
            exportLauncher.launch("notesapp-backup.zip")
        }
        DividerLine()
        ActionRow(
            title = "Import from ZIP",
            subtitle = "Preview, then replace or merge",
            busyLabel = if (importing) "Importing…" else null,
            enabled = !busy
        ) {
            importLauncher.launch(arrayOf("application/zip", "application/octet-stream"))
        }
        pending?.let { preview ->
            DividerLine()
            BasicText(
                text = if (preview.isIntact) {
                    "Ready to import ${preview.manifest.noteCount} notes"
                } else {
                    "Import blocked: backup verification failed"
                },
                style = AuraType.label.copy(
                    color = if (preview.isIntact) tokens.colors.textSecondary else tokens.colors.danger
                ),
                modifier = Modifier.padding(vertical = 8.dp)
            )
            if (!preview.isIntact) {
                ThemeChip(
                    "Cancel",
                    selected = false,
                    onClick = viewModel::cancelImport,
                    modifier = Modifier.fillMaxWidth()
                )
            } else if (!confirmReplace) {
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    // Merge stays one-tap safe; Replace only expands the danger confirm.
                    ThemeChip("Merge", selected = false, onClick = { if (!busy) viewModel.confirmImport(ImportMode.MERGE) }, modifier = Modifier.weight(1f))
                    ThemeChip("Replace", selected = false, onClick = { if (!busy) confirmReplace = true }, modifier = Modifier.weight(1f))
                    ThemeChip("Cancel", selected = false, onClick = { viewModel.cancelImport() }, modifier = Modifier.weight(1f))
                }
            } else {
                BasicText(
                    "Replace all current notes with this backup? This can't be undone",
                    style = AuraType.label.copy(color = tokens.colors.danger),
                    modifier = Modifier.padding(bottom = 10.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    ThemeChip("Cancel", selected = false, onClick = { confirmReplace = false }, modifier = Modifier.weight(1f))
                    DangerButton("Replace all", onClick = {
                        if (!busy) viewModel.confirmImport(ImportMode.REPLACE)
                    }, modifier = Modifier.weight(1f))
                }
            }
        }
        error?.let { err ->
            DividerLine()
            BasicText(
                err.friendly,
                style = AuraType.label.copy(color = tokens.colors.danger),
                modifier = Modifier.padding(top = 8.dp)
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ThemeChip("Retry", selected = false, onClick = { showErrorDetails = false; viewModel.retry() }, modifier = Modifier.weight(1f))
                ThemeChip(
                    if (showErrorDetails) "Hide details" else "Show details",
                    selected = false,
                    onClick = { showErrorDetails = !showErrorDetails },
                    modifier = Modifier.weight(1f)
                )
            }
            if (showErrorDetails) {
                BasicText(
                    err.detail,
                    style = AuraType.bodySm.copy(color = tokens.colors.textSecondary),
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
        status?.let {
            DividerLine()
            BasicText(it, style = AuraType.label.copy(color = tokens.colors.textSecondary), modifier = Modifier.padding(top = 8.dp))
        }
    }
}

@Composable
private fun ActionRow(
    title: String,
    subtitle: String,
    busyLabel: String? = null,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(tokens.radii.sm))
            .auraPress(interaction)
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                onClick = onClick
            )
            .padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f).alpha(if (enabled) 1f else 0.45f)) {
            BasicText(title, style = AuraType.bodyLg.copy(color = tokens.colors.textPrimary))
            BasicText(subtitle, style = AuraType.bodySm.copy(color = tokens.colors.textSecondary))
        }
        busyLabel?.let {
            BasicText(it, style = AuraType.label.copy(color = tokens.colors.accent))
        }
    }
}

/** Filled danger button — the ONLY control that triggers a destructive replace (ux.md P0-1). */
@Composable
private fun DangerButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction, tint = true)
            .background(tokens.colors.danger)
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        BasicText(
            text = label,
            style = AuraType.label.copy(color = tokens.colors.background, textAlign = TextAlign.Center)
        )
    }
}

@Composable
private fun BasicHeader(text: String) {
    val tokens = Aura.tokens
    androidx.compose.foundation.text.BasicText(
        text = text,
        style = AuraType.titleLg.copy(color = tokens.colors.textPrimary)
    )
}

@Composable
private fun BasicRowLabel(text: String) {
    val tokens = Aura.tokens
    androidx.compose.foundation.text.BasicText(
        text = text,
        style = AuraType.body.copy(color = tokens.colors.textPrimary)
    )
}

@Composable
private fun ThemeChip(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val tokens = Aura.tokens
    val t by animateFloatAsState(
        if (selected) 1f else 0f,
        spring(dampingRatio = Spring.DampingRatioMediumBouncy, stiffness = Spring.StiffnessLow),
        label = "chip"
    )
    val bg = lerp(tokens.colors.surface, tokens.colors.accent.copy(alpha = 0.9f), t)
    val fg = lerp(tokens.colors.textSecondary, tokens.colors.background, t)
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier = modifier
            .height(44.dp)
            .clip(RoundedCornerShape(tokens.radii.pill))
            .auraPress(interaction)
            .background(bg)
            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.pill))
            .clickable(
                interactionSource = interaction,
                indication = null,
                onClick = onClick
            ),
        contentAlignment = Alignment.Center
    ) {
        androidx.compose.foundation.text.BasicText(
            text = label,
            style = AuraType.label.copy(color = fg, textAlign = TextAlign.Center)
        )
    }
}

@Composable
private fun DividerLine() {
    val tokens = Aura.tokens
    Box(
        Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(tokens.colors.outline)
    )
}
