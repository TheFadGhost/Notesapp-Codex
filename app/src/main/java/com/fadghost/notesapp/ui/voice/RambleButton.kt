package com.fadghost.notesapp.ui.voice

import android.os.SystemClock
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.fadghost.notesapp.ui.components.AuraGlyph
import com.fadghost.notesapp.ui.components.Glyph
import com.fadghost.notesapp.ui.theme.Aura

/**
 * Aura mic control implementing tap-to-toggle and hold-to-talk. Pointer tracking is by id and
 * deliberately ignores bounds after DOWN, so sliding a thumb off the circle cannot silently stop
 * or discard a recording. Accessibility click retains the simpler toggle behaviour.
 */
@Composable
fun RambleButton(
    recording: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    size: Dp = 58.dp
) {
    val tokens = Aura.tokens
    val machine = remember { RambleGestureStateMachine() }
    var gesture by remember { mutableStateOf(RambleGestureState(recording = recording)) }
    val latestStart by rememberUpdatedState(onStart)
    val latestStop by rememberUpdatedState(onStop)

    fun dispatch(event: RambleGestureEvent) {
        val result = machine.reduce(gesture, event)
        gesture = result.state
        when (result.command) {
            RambleGestureCommand.START -> latestStart()
            RambleGestureCommand.STOP -> latestStop()
            null -> Unit
        }
    }

    LaunchedEffect(recording) {
        dispatch(RambleGestureEvent.ExternalRecording(recording))
    }

    val scale by animateFloatAsState(
        targetValue = if (gesture.pressed) 0.92f else 1f,
        animationSpec = spring(stiffness = 650f),
        label = "rambleMicPress"
    )
    val active = recording || gesture.recording
    val background = if (active) tokens.colors.accent else tokens.colors.surface
    val foreground = if (active) tokens.colors.background else tokens.colors.accent

    Box(
        modifier
            .size(size)
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
                alpha = if (enabled) 1f else 0.45f
            }
            .clip(CircleShape)
            .background(background)
            .border(1.dp, if (active) tokens.colors.accent else tokens.colors.outline, CircleShape)
            .semantics {
                role = Role.Button
                contentDescription = if (active) "Stop voice ramble" else "Record voice ramble"
                if (!enabled) disabled()
                onClick {
                    if (!enabled) return@onClick false
                    if (active) latestStop() else latestStart()
                    true
                }
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                awaitEachGesture {
                    var fingerDown = false
                    try {
                        val down = awaitFirstDown(requireUnconsumed = false)
                        fingerDown = true
                        dispatch(RambleGestureEvent.Down(SystemClock.uptimeMillis()))
                        down.consume()
                        while (fingerDown) {
                            val event = awaitPointerEvent(PointerEventPass.Main)
                            val change = event.changes.firstOrNull { it.id == down.id }
                            if (change == null) {
                                dispatch(RambleGestureEvent.Cancel)
                                fingerDown = false
                            } else {
                                change.consume()
                                if (change.changedToUpIgnoreConsumed()) {
                                    dispatch(RambleGestureEvent.Up(SystemClock.uptimeMillis()))
                                    fingerDown = false
                                }
                            }
                        }
                    } finally {
                        if (fingerDown) dispatch(RambleGestureEvent.Cancel)
                    }
                }
            },
        contentAlignment = Alignment.Center
    ) {
        AuraGlyph(Glyph.MIC, foreground, Modifier.size(size * 0.42f))
    }
}
