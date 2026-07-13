package com.fadghost.notesapp.ui.diary

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fadghost.notesapp.ui.ai.SoftButton
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType
import com.fadghost.notesapp.ui.voice.RambleButton
import com.fadghost.notesapp.ui.voice.VoiceWaveform
import java.time.LocalDate

/** Compact 48dp entry point; it never speaks as Folio or starts recording by itself. */
@Composable
fun DiaryVoiceEntryButton(onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        modifier
            .size(48.dp)
            .clip(CircleShape)
            .auraPress(interaction, tint = true)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "Transcribe into diary" },
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(Glyph.MIC, tokens.colors.accent, Modifier.size(21.dp))
    }
}

/**
 * Aura-only transcript sheet. Closing it is deliberately non-destructive: RecordingService and
 * transcription continue, and reopening the mic for the same date reattaches to the session.
 */
@Composable
fun DiaryVoiceCaptureSheet(
    visible: Boolean,
    date: LocalDate,
    viewModel: DiaryVoiceViewModel,
    onInsert: (String) -> Unit,
    onDismiss: () -> Unit
) {
    if (!visible) return
    val tokens = Aura.tokens
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
    val latestDate by rememberUpdatedState(date)
    var permissionGranted by remember(context) {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var notificationsGranted by remember(context) {
        mutableStateOf(
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var permissionDenied by remember(date) { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        permissionGranted = result[Manifest.permission.RECORD_AUDIO] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            result[Manifest.permission.POST_NOTIFICATIONS] == true ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        permissionDenied = !permissionGranted
        if (permissionGranted) viewModel.start(latestDate) else viewModel.onPermissionDenied()
    }

    LaunchedEffect(visible, date) { viewModel.attach(date) }
    DisposableEffect(lifecycleOwner, context) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                permissionGranted = ContextCompat.checkSelfPermission(
                    context,
                    Manifest.permission.RECORD_AUDIO
                ) == PackageManager.PERMISSION_GRANTED
                notificationsGranted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                    ContextCompat.checkSelfPermission(
                        context,
                        Manifest.permission.POST_NOTIFICATIONS
                    ) == PackageManager.PERMISSION_GRANTED
                if (permissionGranted) permissionDenied = false
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }
    BackHandler(onBack = onDismiss)

    fun requestStart() {
        val microphoneReady = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        val notificationsReady = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
        permissionGranted = microphoneReady
        notificationsGranted = notificationsReady
        if (microphoneReady && notificationsReady) {
            viewModel.start(date)
        } else {
            permissionLauncher.launch(
                buildList {
                    if (!microphoneReady) add(Manifest.permission.RECORD_AUDIO)
                    if (!notificationsReady && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                }.toTypedArray()
            )
        }
    }

    val ownsDate = state.date == null || state.date == date
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = tokens.elevation.scrim))
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss)
        )
        Column(
            Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .clip(RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg))
                .background(tokens.colors.surface)
                .border(
                    1.dp,
                    tokens.colors.outline,
                    RoundedCornerShape(topStart = tokens.radii.lg, topEnd = tokens.radii.lg)
                )
                .clickable(remember { MutableInteractionSource() }, indication = null, onClick = {})
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 18.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    BasicText("VOICE TO DIARY", style = AuraType.labelSm.copy(color = tokens.colors.accent))
                    BasicText(date.toString(), style = AuraType.title.copy(color = tokens.colors.textPrimary))
                }
                SheetIcon(Glyph.CLOSE, "Hide voice sheet", onDismiss)
            }
            Spacer(Modifier.size(16.dp))

            if (!ownsDate) {
                MessageBlock(
                    title = "Another entry has a recording",
                    body = state.error ?: "Finish or discard it before starting this one."
                )
                Spacer(Modifier.size(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    SoftButton("Discard it", filled = false, onClick = viewModel::discard)
                    SoftButton("Close", filled = true, onClick = onDismiss)
                }
                return@Column
            }

            when (state.stage) {
                DiaryVoiceStage.IDLE,
                DiaryVoiceStage.PREPARING,
                DiaryVoiceStage.RECORDING,
                DiaryVoiceStage.PAUSED -> DiaryCaptureBody(
                    state = state,
                    permissionGranted = permissionGranted,
                    permissionDenied = permissionDenied,
                    notificationsGranted = notificationsGranted,
                    onRequestPermission = ::requestStart,
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:${context.packageName}")
                            )
                        )
                    },
                    onStart = ::requestStart,
                    onStop = viewModel::stop,
                    onPause = viewModel::pause,
                    onResume = viewModel::resume,
                    onDiscard = viewModel::discard,
                    onHide = onDismiss
                )

                DiaryVoiceStage.PROCESSING -> {
                    MessageBlock(state.progress ?: "Transcribing…", "The audio stays private to this diary session.")
                    Spacer(Modifier.size(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoftButton("Discard", filled = false, onClick = viewModel::discard)
                        SoftButton("Hide", filled = true, onClick = onDismiss)
                    }
                }

                DiaryVoiceStage.OFFLINE -> {
                    MessageBlock(
                        "Audio saved offline",
                        state.error ?: "Reconnect, then retry. Nothing has been inserted yet."
                    )
                    Spacer(Modifier.size(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoftButton("Discard", filled = false, onClick = viewModel::discard)
                        SoftButton("Retry", filled = true, onClick = viewModel::retry)
                    }
                }

                DiaryVoiceStage.READY -> {
                    MessageBlock("Transcript ready", "Review it below, then insert it at your current selection.")
                    Spacer(Modifier.size(10.dp))
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(max = 180.dp)
                            .clip(RoundedCornerShape(tokens.radii.md))
                            .background(tokens.colors.background)
                            .border(1.dp, tokens.colors.outline, RoundedCornerShape(tokens.radii.md))
                            .verticalScroll(rememberScrollState())
                            .padding(14.dp)
                    ) {
                        BasicText(state.transcript.orEmpty(), style = AuraType.body.copy(color = tokens.colors.textPrimary))
                    }
                    Spacer(Modifier.size(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoftButton("Discard", filled = false, onClick = viewModel::discard)
                        SoftButton("Insert", filled = true, onClick = {
                            val transcript = state.transcript?.takeIf { it.isNotBlank() } ?: return@SoftButton
                            onInsert(transcript)
                            viewModel.acknowledgeInserted()
                            onDismiss()
                        })
                    }
                }

                DiaryVoiceStage.ERROR -> {
                    MessageBlock("Couldn't transcribe", state.error ?: "The audio is still safe.")
                    Spacer(Modifier.size(16.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        SoftButton("Discard", filled = false, onClick = viewModel::discard)
                        if (state.hasSavedAudio) SoftButton("Retry", filled = true, onClick = viewModel::retry)
                    }
                }
            }
        }
    }
}

/**
 * One stable call site owns [RambleButton] from idle through PREPARING/RECORDING. Keeping that
 * composition identity lets a hold that starts capture still receive its eventual UP, including
 * when the thumb slips outside the circle.
 */
@Composable
private fun DiaryCaptureBody(
    state: DiaryVoiceUiState,
    permissionGranted: Boolean,
    permissionDenied: Boolean,
    notificationsGranted: Boolean,
    onRequestPermission: () -> Unit,
    onOpenSettings: () -> Unit,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onDiscard: () -> Unit,
    onHide: () -> Unit
) {
    val tokens = Aura.tokens
    val active = state.stage != DiaryVoiceStage.IDLE
    val preparing = state.stage == DiaryVoiceStage.PREPARING

    if (!active && !permissionGranted) {
        MessageBlock(
            title = if (permissionDenied) "Microphone is off" else "Microphone permission",
            body = if (permissionDenied) {
                "Allow microphone access, or enable it in Settings, then try again."
            } else {
                "Notesapp only starts recording after Android confirms your permission."
            }
        )
        Spacer(Modifier.size(16.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (permissionDenied) {
                SoftButton("Open Settings", filled = false, onClick = onOpenSettings)
            }
            SoftButton(
                if (permissionDenied) "Try again" else "Continue",
                filled = true,
                onClick = onRequestPermission
            )
        }
        return
    }

    BasicText(
        when {
            !active -> "Speak into this entry"
            preparing -> "Starting microphone…"
            state.paused -> "Paused"
            else -> "Recording"
        },
        style = if (active) {
            AuraType.label.copy(
                color = if (state.paused || preparing) {
                    tokens.colors.textSecondary
                } else {
                    tokens.colors.accent
                }
            )
        } else {
            AuraType.title.copy(color = tokens.colors.textPrimary)
        }
    )
    Spacer(Modifier.size(6.dp))
    if (active) {
        BasicText(
            formatElapsed(state.elapsedMs),
            style = AuraType.titleLg.copy(color = tokens.colors.textPrimary)
        )
        VoiceWaveform(
            state.amplitudes.ifEmpty { List(24) { 0f } },
            if (state.paused) tokens.colors.textSecondary else tokens.colors.accent,
            Modifier.padding(vertical = 6.dp)
        )
    } else {
        BasicText(
            "Tap to keep recording, or hold and release for a quick thought.",
            style = AuraType.body.copy(
                color = tokens.colors.textSecondary,
                textAlign = TextAlign.Center
            )
        )
        Spacer(Modifier.size(12.dp))
    }
    RambleButton(
        recording = active,
        onStart = onStart,
        onStop = onStop,
        size = 62.dp
    )
    state.error?.let {
        Spacer(Modifier.size(10.dp))
        BasicText(
            it,
            style = AuraType.label.copy(color = tokens.colors.danger, textAlign = TextAlign.Center)
        )
    }
    if (!notificationsGranted) {
        Spacer(Modifier.size(10.dp))
        BasicText(
            "Notifications are off, so background recording controls may not be visible.",
            style = AuraType.labelSm.copy(
                color = tokens.colors.textSecondary,
                textAlign = TextAlign.Center
            )
        )
    }
    if (active) {
        Spacer(Modifier.size(14.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Discard", filled = false, onClick = onDiscard)
            if (!preparing) {
                SoftButton(
                    if (state.paused) "Resume" else "Pause",
                    filled = false,
                    onClick = { if (state.paused) onResume() else onPause() }
                )
            }
            SoftButton("Hide", filled = false, onClick = onHide)
        }
    }
}

@Composable
private fun MessageBlock(title: String, body: String) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            title,
            style = AuraType.title.copy(color = tokens.colors.textPrimary, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.size(6.dp))
        BasicText(
            body,
            style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
    }
}

@Composable
private fun SheetIcon(glyph: Glyph, description: String, onClick: () -> Unit) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .auraPress(interaction)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(glyph, tokens.colors.textSecondary, Modifier.size(19.dp))
    }
}

private fun formatElapsed(ms: Long): String {
    val seconds = ms / 1_000L
    return "%d:%02d".format(seconds / 60L, seconds % 60L)
}
