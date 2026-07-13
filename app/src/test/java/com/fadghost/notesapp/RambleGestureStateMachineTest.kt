package com.fadghost.notesapp

import com.fadghost.notesapp.ui.voice.RambleGestureCommand
import com.fadghost.notesapp.ui.voice.RambleGestureEvent
import com.fadghost.notesapp.ui.voice.RambleGestureState
import com.fadghost.notesapp.ui.voice.RambleGestureStateMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RambleGestureStateMachineTest {
    private val machine = RambleGestureStateMachine(holdThresholdMs = 450L)

    @Test fun quickTapStartsImmediatelyAndLatches() {
        val down = machine.reduce(RambleGestureState(), RambleGestureEvent.Down(1_000L))
        assertEquals(RambleGestureCommand.START, down.command)
        assertTrue(down.state.recording)

        val up = machine.reduce(down.state, RambleGestureEvent.Up(1_100L))
        assertNull(up.command)
        assertTrue(up.state.recording)
        assertFalse(up.state.pressed)
    }

    @Test fun holdStartsOnDownAndStopsOnRelease() {
        val down = machine.reduce(RambleGestureState(), RambleGestureEvent.Down(1_000L))
        val up = machine.reduce(down.state, RambleGestureEvent.Up(1_451L))
        assertEquals(RambleGestureCommand.STOP, up.command)
        assertFalse(up.state.recording)
    }

    @Test fun secondTapStopsLatchedRecordingOnRelease() {
        val down = machine.reduce(
            RambleGestureState(recording = true),
            RambleGestureEvent.Down(2_000L)
        )
        assertNull(down.command)
        assertTrue(down.state.recording)

        val up = machine.reduce(down.state, RambleGestureEvent.Up(2_030L))
        assertEquals(RambleGestureCommand.STOP, up.command)
        assertFalse(up.state.recording)
    }

    @Test fun cancelledFreshPressStopsCaptureItStarted() {
        val down = machine.reduce(RambleGestureState(), RambleGestureEvent.Down(1_000L))
        val cancelled = machine.reduce(down.state, RambleGestureEvent.Cancel)
        assertEquals(RambleGestureCommand.STOP, cancelled.command)
        assertFalse(cancelled.state.recording)
    }

    @Test fun cancelledSecondPressDoesNotStopExistingCapture() {
        val down = machine.reduce(
            RambleGestureState(recording = true),
            RambleGestureEvent.Down(1_000L)
        )
        val cancelled = machine.reduce(down.state, RambleGestureEvent.Cancel)
        assertNull(cancelled.command)
        assertTrue(cancelled.state.recording)
        assertFalse(cancelled.state.pressed)
    }

    @Test fun asynchronousExternalStateCannotBreakActiveHold() {
        val down = machine.reduce(RambleGestureState(), RambleGestureEvent.Down(1_000L))
        val stale = machine.reduce(down.state, RambleGestureEvent.ExternalRecording(false))
        assertTrue(stale.state.recording)
        assertTrue(stale.state.pressed)
    }
}
