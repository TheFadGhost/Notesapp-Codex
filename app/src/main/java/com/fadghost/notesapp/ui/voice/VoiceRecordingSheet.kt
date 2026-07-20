package com.fadghost.notesapp.ui.voice

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.Animatable
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import com.fadghost.notesapp.ui.ai.SoftButton
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.theme.LocalReduceMotion
import com.fadghost.notesapp.ui.theme.MotionTokens
import com.fadghost.notesapp.data.audio.VoiceRecordingSession
import com.fadghost.notesapp.data.audio.VoiceSessionState
import com.fadghost.notesapp.ui.overlay.RecordingOverlayLauncher
import kotlinx.coroutines.launch

/**
 * Spring-up Aura recording sheet (PLAN.md §5): friendly RECORD_AUDIO pre-prompt +
 * denied state, live waveform + elapsed timer, pause/resume, stop, discard, then the
 * STT progress states (uploading n/m → transcribing → done) with cancel, plus an
 * offline queued state. New-note mode commits + hands the note back; append mode
 * hands the transcript back for the editor to insert at the caret. No Material.
 */
@Composable
fun VoiceRecordingSheet(
    visible: Boolean,
    targetNoteId: Long,
    appendMode: Boolean,
    transcriptOnly: Boolean = false,
    onDismiss: () -> Unit,
    onNewNoteReady: (Long) -> Unit = {},
    onTranscriptReady: (String) -> Unit = {},
    viewModel: VoiceRecordViewModel = hiltViewModel()
) {
    if (!visible) return
    val tokens = Aura.tokens
    val context = LocalContext.current
    val density = LocalDensity.current
    val scope = rememberCoroutineScope()
    val reduceMotion = LocalReduceMotion.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val session by VoiceRecordingSession.state.collectAsStateWithLifecycle()

    val sheetHeightPx = with(density) { 420.dp.toPx() }
    val offsetY = remember { Animatable(sheetHeightPx) }
    val scrimAlpha = remember { Animatable(0f) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) viewModel.startRecording() else viewModel.onPermissionDenied() }
    val overlayPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {
        if (RecordingOverlayLauncher.canDraw(context) &&
            (session is VoiceSessionState.Starting || session is VoiceSessionState.Recording)
        ) {
            RecordingOverlayLauncher.show(context)
        }
    }

    LaunchedEffect(session) {
        when (session) {
            is VoiceSessionState.Starting -> if (RecordingOverlayLauncher.canDraw(context)) {
                RecordingOverlayLauncher.show(context)
            }
            is VoiceSessionState.Recording -> if (RecordingOverlayLauncher.canDraw(context)) {
                RecordingOverlayLauncher.show(context)
            }
            else -> RecordingOverlayLauncher.hide(context)
        }
    }

    fun hasPermission() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

    LaunchedEffect(visible, targetNoteId, appendMode, transcriptOnly) {
        launch { scrimAlpha.animateTo(1f, tween(MotionTokens.SheetScrimInMs)) }
        offsetY.animateTo(0f, MotionTokens.medium(reduceMotion))
        viewModel.begin(targetNoteId, appendMode, transcriptOnly)
        if (hasPermission()) viewModel.startRecording()
    }

    suspend fun animateOut() {
        scope.launch { scrimAlpha.animateTo(0f, tween(MotionTokens.SheetScrimOutMs)) }
        offsetY.animateTo(sheetHeightPx, tween(MotionTokens.SheetDropMs))
    }

    // Terminal-state routing.
    LaunchedEffect(state.phase) {
        when (state.phase) {
            VoicePhase.DONE -> {
                animateOut()
                if (appendMode || transcriptOnly) state.transcript?.let { onTranscriptReady(it) }
                else state.committedNoteId?.let { onNewNoteReady(it) }
                onDismiss()
            }
            VoicePhase.QUEUED -> { /* keep sheet; user taps Done */ }
            else -> {}
        }
    }

    fun dismiss(discard: Boolean) {
        scope.launch {
            if (discard) viewModel.discard()
            animateOut()
            onDismiss()
        }
    }
    // System back = the safe dismiss (never discards a take).
    androidx.activity.compose.BackHandler { dismiss(discard = false) }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { alpha = scrimAlpha.value }
                .background(tokens.colors.scrimTint.copy(alpha = tokens.elevation.scrim))
                .clickable(remember { MutableInteractionSource() }, indication = null) {
                    // Tapping the scrim discards an idle/permission state; ignore mid-record.
                    if (state.phase == VoicePhase.REQUEST_PERMISSION || state.phase == VoicePhase.DENIED) dismiss(true)
                }
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .graphicsLayer { translationY = offsetY.value }
                .clip(RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
                .background(tokens.colors.surfaceTranslucent)
                .border(1.dp, tokens.colors.outline, RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
                .navigationBarsPadding()
                .padding(horizontal = 20.dp)
                .padding(top = 14.dp, bottom = 24.dp)
        ) {
            com.fadghost.notesapp.ui.components.GrabHandle(Modifier.align(Alignment.CenterHorizontally))
            Spacer(Modifier.height(16.dp))
            if ((state.phase == VoicePhase.STARTING || state.phase == VoicePhase.RECORDING) &&
                !RecordingOverlayLauncher.canDraw(context)
            ) {
                BasicText(
                    "Enable floating controls",
                    style = AuraType.label.copy(color = tokens.colors.accent),
                    modifier = Modifier
                        .align(Alignment.CenterHorizontally)
                        .clip(RoundedCornerShape(tokens.radii.pill))
                        .clickable {
                            overlayPermissionLauncher.launch(RecordingOverlayLauncher.permissionIntent(context))
                        }
                        .padding(horizontal = 12.dp, vertical = 7.dp)
                )
                Spacer(Modifier.height(8.dp))
            }

            when (state.phase) {
                VoicePhase.REQUEST_PERMISSION ->
                    if (hasPermission()) RecordingBody(state, viewModel, ::dismiss)
                    else PermissionPrompt(
                        onAllow = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                        onNotNow = { dismiss(true) }
                    )
                VoicePhase.DENIED -> DeniedState(
                    onOpenSettings = {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    onNotNow = { dismiss(true) }
                )
                VoicePhase.STARTING -> ProcessingState("Starting recorder…", onCancel = { dismiss(true) })
                VoicePhase.RECORDING -> RecordingBody(state, viewModel, ::dismiss)
                VoicePhase.PROCESSING -> ProcessingState(state.progress ?: "Working…", onCancel = viewModel::cancelProcessing)
                VoicePhase.QUEUED -> QueuedState(onDone = { dismiss(false) })
                VoicePhase.ERROR -> ErrorState(
                    message = state.error ?: "Something went wrong.",
                    canRetry = state.segments.isNotEmpty(),
                    onRetry = viewModel::retry,
                    onDiscard = { dismiss(true) }
                )
                VoicePhase.DONE -> Spacer(Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun PermissionPrompt(onAllow: () -> Unit, onNotNow: () -> Unit) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MicBadge()
        Spacer(Modifier.height(14.dp))
        BasicText("Record a voice ramble", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Notesapp needs microphone access to record and transcribe your voice. Audio stays on your device.",
            style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Not now", filled = false, onClick = onNotNow)
            SoftButton("Allow microphone", filled = true, onClick = onAllow)
        }
    }
}

@Composable
private fun DeniedState(onOpenSettings: () -> Unit, onNotNow: () -> Unit) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MicBadge()
        Spacer(Modifier.height(14.dp))
        BasicText("Microphone is off", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Voice notes need microphone permission. You can enable it in system settings whenever you like.",
            style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Not now", filled = false, onClick = onNotNow)
            SoftButton("Open settings", filled = true, onClick = onOpenSettings)
        }
    }
}

@Composable
private fun RecordingBody(
    state: VoiceUiState,
    vm: VoiceRecordViewModel,
    dismiss: (Boolean) -> Unit
) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        BasicText(
            if (state.paused) "Paused" else "Recording",
            style = AuraType.label.copy(color = if (state.paused) tokens.colors.textSecondary else tokens.colors.accent)
        )
        Spacer(Modifier.height(6.dp))
        BasicText(formatElapsed(state.elapsedMs), style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(16.dp))
        VoiceWaveform(amplitudes = state.amplitudes, color = tokens.colors.accent)
        Spacer(Modifier.height(20.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Destructive control isolated on the far side — it used to sit one slip
            // away from Pause at identical size (council G2). Ramble sheet layout is
            // the reference: safe controls cluster, Discard stands alone.
            CircleControl(Glyph.TRASH, tokens.colors.danger, "Discard") { dismiss(true) }
            Spacer(Modifier.weight(1f))
            CircleControl(
                if (state.paused) Glyph.PLAY else Glyph.PAUSE,
                tokens.colors.textPrimary,
                if (state.paused) "Resume" else "Pause"
            ) { vm.togglePause() }
            // Prominent Stop button.
            val stopInteraction = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .auraPress(stopInteraction, tint = true)
                    .background(tokens.colors.accent)
                    .clickable(interactionSource = stopInteraction, indication = null) { vm.stop() }
                    .semantics { contentDescription = "Stop and transcribe" },
                contentAlignment = Alignment.Center
            ) {
                Box(Modifier.size(22.dp).clip(RoundedCornerShape(4.dp)).background(tokens.colors.background))
            }
        }
    }
}

@Composable
private fun ProcessingState(label: String, onCancel: () -> Unit) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MicBadge()
        Spacer(Modifier.height(14.dp))
        BasicText(label, style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        BasicText("Transcribing your voice note…", style = AuraType.body.copy(color = tokens.colors.textSecondary))
        Spacer(Modifier.height(18.dp))
        SoftButton("Cancel", filled = false, onClick = onCancel)
    }
}

@Composable
private fun QueuedState(onDone: () -> Unit) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MicBadge()
        Spacer(Modifier.height(14.dp))
        BasicText("Queued — you're offline", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        BasicText(
            "Your audio is saved. It'll transcribe automatically when you're back online.",
            style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(18.dp))
        SoftButton("Done", filled = true, onClick = onDone)
    }
}

@Composable
private fun ErrorState(message: String, canRetry: Boolean, onRetry: () -> Unit, onDiscard: () -> Unit) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        MicBadge()
        Spacer(Modifier.height(14.dp))
        BasicText("Couldn't finish", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        BasicText(message, style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center))
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Discard", filled = false, onClick = onDiscard)
            if (canRetry) SoftButton("Retry", filled = true, onClick = onRetry)
        }
    }
}

@Composable
private fun MicBadge() {
    val tokens = Aura.tokens
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .background(tokens.colors.accent.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) { AuraGlyph(Glyph.SPARKLE, tokens.colors.accent, Modifier.size(28.dp)) }
}

@Composable
private fun CircleControl(glyph: Glyph, tint: Color, cd: String, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .auraPress(interaction, tint = true)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = cd },
        contentAlignment = Alignment.Center
    ) { AuraGlyph(glyph, tint, Modifier.size(22.dp)) }
}

private fun formatElapsed(ms: Long): String {
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}
