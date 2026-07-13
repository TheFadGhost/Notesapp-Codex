package com.fadghost.notesapp.ui.voice

/** Commands emitted by the mic gesture. The reducer is pure so awkward slips are testable. */
enum class RambleGestureCommand { START, STOP }

sealed interface RambleGestureEvent {
    data class Down(val uptimeMs: Long) : RambleGestureEvent
    data class Up(val uptimeMs: Long) : RambleGestureEvent
    data object Cancel : RambleGestureEvent
    data class ExternalRecording(val recording: Boolean) : RambleGestureEvent
}

data class RambleGestureState(
    val recording: Boolean = false,
    val press: Press? = null
) {
    data class Press(val downAtMs: Long, val wasRecording: Boolean)
    val pressed: Boolean get() = press != null
}

data class RambleGestureResult(
    val state: RambleGestureState,
    val command: RambleGestureCommand? = null
)

/**
 * Gesture contract:
 * - idle DOWN starts immediately, so holding feels zero-latency;
 * - releasing quickly latches recording (tap-to-toggle);
 * - releasing after [holdThresholdMs] stops (hold-to-talk);
 * - movement/out-of-bounds never enters this reducer, so a finger slip cannot cancel capture;
 * - cancellation stops only a capture this particular press started. It never stops an already
 *   latched recording merely because Android cancelled a second touch.
 */
class RambleGestureStateMachine(
    private val holdThresholdMs: Long = DEFAULT_HOLD_THRESHOLD_MS
) {
    init {
        require(holdThresholdMs >= 0L)
    }

    fun reduce(state: RambleGestureState, event: RambleGestureEvent): RambleGestureResult =
        when (event) {
            is RambleGestureEvent.Down -> onDown(state, event.uptimeMs)
            is RambleGestureEvent.Up -> onUp(state, event.uptimeMs)
            RambleGestureEvent.Cancel -> onCancel(state)
            is RambleGestureEvent.ExternalRecording -> {
                // Do not let the asynchronous service's PREPARING -> RECORDING transition reset
                // an in-flight press. Reconcile once the finger is up.
                if (state.press == null) RambleGestureResult(state.copy(recording = event.recording))
                else RambleGestureResult(state)
            }
        }

    private fun onDown(state: RambleGestureState, now: Long): RambleGestureResult {
        if (state.press != null) return RambleGestureResult(state)
        return if (state.recording) {
            RambleGestureResult(state.copy(press = RambleGestureState.Press(now, wasRecording = true)))
        } else {
            RambleGestureResult(
                state.copy(
                    recording = true,
                    press = RambleGestureState.Press(now, wasRecording = false)
                ),
                RambleGestureCommand.START
            )
        }
    }

    private fun onUp(state: RambleGestureState, now: Long): RambleGestureResult {
        val press = state.press ?: return RambleGestureResult(state)
        val held = (now - press.downAtMs).coerceAtLeast(0L) >= holdThresholdMs
        return if (press.wasRecording || held) {
            RambleGestureResult(
                state.copy(recording = false, press = null),
                RambleGestureCommand.STOP
            )
        } else {
            RambleGestureResult(state.copy(recording = true, press = null))
        }
    }

    private fun onCancel(state: RambleGestureState): RambleGestureResult {
        val press = state.press ?: return RambleGestureResult(state)
        return if (press.wasRecording) {
            RambleGestureResult(state.copy(press = null))
        } else {
            RambleGestureResult(
                state.copy(recording = false, press = null),
                RambleGestureCommand.STOP
            )
        }
    }

    companion object {
        const val DEFAULT_HOLD_THRESHOLD_MS = 450L
    }
}
