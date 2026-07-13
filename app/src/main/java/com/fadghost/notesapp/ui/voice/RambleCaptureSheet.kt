package com.fadghost.notesapp.ui.voice

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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.fadghost.notesapp.data.audio.VoiceSessionPhase
import com.fadghost.notesapp.ui.ai.ExtractCard
import com.fadghost.notesapp.ui.ai.ExtractSheet
import com.fadghost.notesapp.ui.ai.ExtractState
import com.fadghost.notesapp.ui.ai.SoftButton
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.components.auraPress
import com.fadghost.notesapp.ui.theme.Aura
import com.fadghost.notesapp.ui.theme.AuraType

/**
 * Shell overlay for the durable ramble flow.
 *
 * Pass the shell's [RambleViewModel] so foreground capture and persisted review state survive
 * navigation between screens. The default remains useful for previews or a shell whose current
 * ViewModelStoreOwner is already activity scoped. Closing this sheet never stops or discards a
 * capture; only the explicitly labelled controls do that.
 */
@Composable
fun RambleCaptureSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    onOpenNote: (Long) -> Unit,
    viewModel: RambleViewModel = hiltViewModel()
) {
    if (!visible) return

    val tokens = Aura.tokens
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val state by viewModel.state.collectAsStateWithLifecycle()
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
    var permissionDenied by rememberSaveable { mutableStateOf(false) }
    var editingCards by remember(state.sessionId) { mutableStateOf(emptySet<Long>()) }

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
        if (permissionGranted) viewModel.startRamble()
    }

    // Returning from system settings can change permission without invoking the launcher callback.
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

    fun requestPermission() {
        // This launcher is hosted by the currently visible Activity. The ViewModel never starts a
        // microphone foreground service before this visible UI has confirmed permission.
        permissionLauncher.launch(
            buildList {
                if (!permissionGranted) add(Manifest.permission.RECORD_AUDIO)
                if (!notificationsGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    add(Manifest.permission.POST_NOTIFICATIONS)
                }
            }.toTypedArray()
        )
    }

    fun beginCapture() {
        if (permissionGranted && notificationsGranted) viewModel.startRamble() else requestPermission()
    }

    fun openCurrentNote() {
        state.noteId?.let { noteId ->
            viewModel.hideReview()
            onOpenNote(noteId)
            onDismiss()
        }
    }

    val reviewActive = state.reviewVisible && state.cards.isNotEmpty()
    BackHandler {
        if (reviewActive) viewModel.hideReview() else onDismiss()
    }

    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = tokens.elevation.scrim))
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss
                )
        )

        Column(
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .heightIn(max = 620.dp)
                .clip(
                    RoundedCornerShape(
                        topStart = tokens.radii.lg,
                        topEnd = tokens.radii.lg
                    )
                )
                .background(tokens.colors.surfaceTranslucent)
                .border(
                    1.dp,
                    tokens.colors.outline,
                    RoundedCornerShape(
                        topStart = tokens.radii.lg,
                        topEnd = tokens.radii.lg
                    )
                )
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {}
                )
                .navigationBarsPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 12.dp, bottom = 24.dp)
                .semantics { paneTitle = "Voice ramble" }
        ) {
            Box(
                Modifier
                    .align(Alignment.CenterHorizontally)
                    .width(40.dp)
                    .height(5.dp)
                    .clip(CircleShape)
                    .background(tokens.colors.textSecondary.copy(alpha = 0.45f))
            )
            Spacer(Modifier.height(12.dp))
            RambleHeader(onDismiss)
            Spacer(Modifier.height(14.dp))

            when {
                state.captureActive ||
                    (state.phase == null && state.error == null && permissionGranted) -> RambleCaptureBody(
                    state = state,
                    notificationsGranted = notificationsGranted,
                    onStart = ::beginCapture,
                    onPause = viewModel::pause,
                    onResume = viewModel::resume,
                    onStop = viewModel::stop,
                    onDiscard = {
                        viewModel.discard()
                        onDismiss()
                    }
                )

                state.processing -> RambleProcessingBody(state.phase)

                state.phase == VoiceSessionPhase.ERROR ||
                    state.phase == VoiceSessionPhase.INTERRUPTED ||
                    (state.phase == VoiceSessionPhase.AWAITING_CONFIRMATION &&
                        state.cards.isEmpty() && state.error != null) -> RambleErrorBody(
                    message = state.error ?: "The recording was saved, but processing was interrupted.",
                    noteAvailable = state.noteId != null,
                    onRetry = viewModel::retryPipeline,
                    onOpenNote = ::openCurrentNote
                )

                state.phase == VoiceSessionPhase.AWAITING_CONFIRMATION && state.cards.isNotEmpty() ->
                    RambleReviewReadyBody(
                        actionCount = state.cards.size,
                        acceptedCount = state.acceptedCount,
                        onReview = { viewModel.showReview() },
                        onOpenNote = ::openCurrentNote,
                        onDone = onDismiss
                    )

                state.phase == VoiceSessionPhase.COMPLETE -> RambleCompleteBody(
                    acceptedCount = state.acceptedCount,
                    noteAvailable = state.noteId != null,
                    onOpenNote = ::openCurrentNote,
                    onRecordAnother = ::beginCapture,
                    onDone = onDismiss
                )

                state.phase == VoiceSessionPhase.DISCARDED -> RambleDiscardedBody(
                    onRecordAgain = ::beginCapture,
                    onDone = onDismiss
                )

                state.error != null -> RambleStartErrorBody(
                    message = state.error.orEmpty(),
                    onRetry = ::beginCapture,
                    onDismiss = onDismiss
                )

                !permissionGranted -> RamblePermissionBody(
                    denied = permissionDenied,
                    onRequest = ::requestPermission,
                    onOpenSettings = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.fromParts("package", context.packageName, null)
                            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    },
                    onDismiss = onDismiss
                )

                else -> RambleStartErrorBody(
                    message = "This voice session is no longer available.",
                    onRetry = ::beginCapture,
                    onDismiss = onDismiss
                )
            }
        }

        // The extraction UI is shared with editor/Ask, while its edits and busy flags are projected
        // from the durable ramble manifest. Dismissing it persists the cards for later reopening.
        ExtractSheet(
            state = ExtractState(
                active = reviewActive,
                cards = state.cards.map { card ->
                    ExtractCard(
                        id = card.id,
                        action = card.action,
                        editing = card.id in editingCards,
                        revising = card.busy
                    )
                },
                warnings = state.warnings + listOfNotNull(
                    state.error?.takeIf { state.cards.isNotEmpty() }
                ),
                acceptedCount = state.acceptedCount
            ),
            onAccept = { id ->
                editingCards -= id
                viewModel.acceptCard(id)
            },
            onReject = { id ->
                editingCards -= id
                viewModel.rejectCard(id)
            },
            onBeginEdit = { id -> editingCards += id },
            onCancelEdit = { id -> editingCards -= id },
            onApplyEdit = { id, title, at ->
                editingCards -= id
                viewModel.editCard(id, title, at)
            },
            onRevise = viewModel::reviseCard,
            onAcceptAll = {
                editingCards = emptySet()
                viewModel.acceptAll()
            },
            onDismiss = viewModel::hideReview
        )
    }
}

@Composable
private fun RambleHeader(onDismiss: () -> Unit) {
    val tokens = Aura.tokens
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AuraGlyph(Glyph.MIC, tokens.colors.accent, Modifier.size(24.dp))
        Spacer(Modifier.width(10.dp))
        BasicText("Voice ramble", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.weight(1f))
        RambleIconButton(Glyph.CLOSE, tokens.colors.textSecondary, "Close voice ramble", onDismiss)
    }
}

@Composable
private fun RambleCaptureBody(
    state: RambleUiState,
    notificationsGranted: Boolean,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onStop: () -> Unit,
    onDiscard: () -> Unit
) {
    val tokens = Aura.tokens
    val active = state.captureActive
    val preparing = state.phase == VoiceSessionPhase.PREPARING
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        BasicText(
            when {
                !active -> "Say it naturally"
                preparing -> "Starting microphone…"
                state.paused -> "Paused"
                else -> "Recording"
            },
            style = if (active) AuraType.label.copy(
                color = if (state.paused || preparing) tokens.colors.textSecondary
                else tokens.colors.accent
            ) else AuraType.titleSm.copy(
                color = tokens.colors.textPrimary
            )
        )
        Spacer(Modifier.height(6.dp))
        if (active) {
            BasicText(
                formatRambleElapsed(state.elapsedMs),
                style = AuraType.titleLg.copy(color = tokens.colors.textPrimary)
            )
            Spacer(Modifier.height(10.dp))
            VoiceWaveform(
                amplitudes = state.amplitudes.ifEmpty { List(24) { 0f } },
                color = if (state.paused) tokens.colors.textSecondary else tokens.colors.accent
            )
            Spacer(Modifier.height(14.dp))
        } else {
            BasicText(
                "We’ll turn the ramble into a clean note, then let you confirm any events or reminders.",
                style = AuraType.body.copy(
                    color = tokens.colors.textSecondary,
                    textAlign = TextAlign.Center
                )
            )
            Spacer(Modifier.height(22.dp))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterHorizontally),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (active) {
                RambleIconButton(Glyph.TRASH, tokens.colors.danger, "Discard recording", onDiscard)
            } else {
                Spacer(Modifier.size(48.dp))
            }
            RambleButton(
                recording = active,
                onStart = onStart,
                onStop = onStop,
                size = 72.dp
            )
            if (active) {
                RambleIconButton(
                    glyph = if (state.paused) Glyph.MIC else Glyph.CHECK,
                    tint = tokens.colors.textPrimary,
                    description = if (state.paused) "Resume recording" else "Pause recording",
                    onClick = if (state.paused) onResume else onPause
                )
            } else {
                Spacer(Modifier.size(48.dp))
            }
        }
        Spacer(Modifier.height(12.dp))
        BasicText(
            when {
                !active -> "Tap to keep recording · hold and release to finish"
                state.paused -> "Resume when ready, or tap the mic to finish."
                else -> "Tap the mic to finish. Sliding your finger away won’t lose the recording."
            },
            style = AuraType.label.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
        if (active) {
            Spacer(Modifier.height(8.dp))
            BasicText(
                if (notificationsGranted) {
                    "Closing this sheet is safe — recording continues in the background."
                } else {
                    "Notifications are off, so background recording controls may not be visible."
                },
                style = AuraType.labelSm.copy(
                    color = tokens.colors.textSecondary,
                    textAlign = TextAlign.Center
                )
            )
        }
    }
}

@Composable
private fun RambleProcessingBody(phase: VoiceSessionPhase?) {
    val tokens = Aura.tokens
    val title = when (phase) {
        VoiceSessionPhase.RECORDED -> "Recording saved"
        VoiceSessionPhase.TRANSCRIBING -> "Transcribing your ramble…"
        VoiceSessionPhase.ORGANIZING -> "Organising your note…"
        else -> "Working on your note…"
    }
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        RambleStatusBadge(Glyph.SPARKLE)
        Spacer(Modifier.height(14.dp))
        BasicText(title, style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        BasicText(
            if (phase == VoiceSessionPhase.RECORDED) {
                "Your audio is safe. Processing will continue as soon as a connection is available."
            } else {
                "The recording is safe. You can close this sheet while we keep working."
            },
            style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
    }
}

@Composable
private fun RambleReviewReadyBody(
    actionCount: Int,
    acceptedCount: Int,
    onReview: () -> Unit,
    onOpenNote: () -> Unit,
    onDone: () -> Unit
) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        RambleStatusBadge(Glyph.CHECK)
        Spacer(Modifier.height(14.dp))
        BasicText("Your note is ready", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        BasicText(
            buildString {
                append("Review ")
                append(actionCount)
                append(if (actionCount == 1) " suggested action" else " suggested actions")
                if (acceptedCount > 0) append(" · $acceptedCount already added")
            },
            style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Open note", filled = false, onClick = onOpenNote)
            SoftButton("Review actions", filled = true, onClick = onReview)
        }
        Spacer(Modifier.height(10.dp))
        SoftButton("Done for now", filled = false, onClick = onDone)
    }
}

@Composable
private fun RambleCompleteBody(
    acceptedCount: Int,
    noteAvailable: Boolean,
    onOpenNote: () -> Unit,
    onRecordAnother: () -> Unit,
    onDone: () -> Unit
) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        RambleStatusBadge(Glyph.CHECK)
        Spacer(Modifier.height(14.dp))
        BasicText("All set", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        BasicText(
            if (acceptedCount > 0) "Your note is ready and $acceptedCount confirmed action${if (acceptedCount == 1) " was" else "s were"} added."
            else "Your ramble is now a clean note.",
            style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Done", filled = false, onClick = onDone)
            if (noteAvailable) SoftButton("Open note", filled = true, onClick = onOpenNote)
        }
        Spacer(Modifier.height(10.dp))
        SoftButton("Record another", filled = false, onClick = onRecordAnother)
    }
}

@Composable
private fun RambleErrorBody(
    message: String,
    noteAvailable: Boolean,
    onRetry: () -> Unit,
    onOpenNote: () -> Unit
) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        RambleStatusBadge(Glyph.CLOSE, danger = true)
        Spacer(Modifier.height(14.dp))
        BasicText("Couldn’t finish", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        BasicText(message, style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center))
        Spacer(Modifier.height(8.dp))
        BasicText(
            "Your audio and any note already created are kept safe.",
            style = AuraType.label.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            if (noteAvailable) SoftButton("Open note", filled = false, onClick = onOpenNote)
            SoftButton("Retry", filled = true, onClick = onRetry)
        }
    }
}

@Composable
private fun RambleStartErrorBody(message: String, onRetry: () -> Unit, onDismiss: () -> Unit) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        RambleStatusBadge(Glyph.CLOSE, danger = true)
        Spacer(Modifier.height(14.dp))
        BasicText("Couldn’t start recording", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(6.dp))
        BasicText(message, style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center))
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Not now", filled = false, onClick = onDismiss)
            SoftButton("Try again", filled = true, onClick = onRetry)
        }
    }
}

@Composable
private fun RamblePermissionBody(
    denied: Boolean,
    onRequest: () -> Unit,
    onOpenSettings: () -> Unit,
    onDismiss: () -> Unit
) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        RambleStatusBadge(Glyph.MIC)
        Spacer(Modifier.height(14.dp))
        BasicText(
            if (denied) "Microphone access is off" else "Allow microphone access",
            style = AuraType.titleSm.copy(color = tokens.colors.textPrimary)
        )
        Spacer(Modifier.height(6.dp))
        BasicText(
            if (denied) {
                "Turn on microphone access in Android settings, then come back to start a ramble."
            } else {
                "Notesapp only starts recording after Android confirms your permission."
            },
            style = AuraType.body.copy(color = tokens.colors.textSecondary, textAlign = TextAlign.Center)
        )
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Not now", filled = false, onClick = onDismiss)
            SoftButton(
                if (denied) "Try permission again" else "Continue",
                filled = true,
                onClick = onRequest
            )
        }
        if (denied) {
            Spacer(Modifier.height(10.dp))
            SoftButton("Open Android settings", filled = false, onClick = onOpenSettings)
        }
    }
}

@Composable
private fun RambleDiscardedBody(onRecordAgain: () -> Unit, onDone: () -> Unit) {
    val tokens = Aura.tokens
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        RambleStatusBadge(Glyph.TRASH)
        Spacer(Modifier.height(14.dp))
        BasicText("Recording discarded", style = AuraType.titleSm.copy(color = tokens.colors.textPrimary))
        Spacer(Modifier.height(18.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftButton("Done", filled = false, onClick = onDone)
            SoftButton("Record again", filled = true, onClick = onRecordAgain)
        }
    }
}

@Composable
private fun RambleStatusBadge(glyph: Glyph, danger: Boolean = false) {
    val tokens = Aura.tokens
    val tint = if (danger) tokens.colors.danger else tokens.colors.accent
    Box(
        Modifier
            .size(54.dp)
            .clip(CircleShape)
            .background(tint.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(glyph, tint, Modifier.size(27.dp))
    }
}

@Composable
private fun RambleIconButton(
    glyph: Glyph,
    tint: Color,
    description: String,
    onClick: () -> Unit
) {
    val tokens = Aura.tokens
    val interaction = remember { MutableInteractionSource() }
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .auraPress(interaction, tint = true)
            .background(tokens.colors.surface)
            .border(1.dp, tokens.colors.outline, CircleShape)
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = description },
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(glyph, tint, Modifier.size(21.dp))
    }
}

private fun formatRambleElapsed(elapsedMs: Long): String {
    val totalSeconds = (elapsedMs.coerceAtLeast(0L) / 1_000L)
    val minutes = totalSeconds / 60L
    val seconds = totalSeconds % 60L
    return "$minutes:${seconds.toString().padStart(2, '0')}"
}
