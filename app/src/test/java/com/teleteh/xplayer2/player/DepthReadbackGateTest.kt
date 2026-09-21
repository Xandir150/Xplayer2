package com.teleteh.xplayer2.player

import org.junit.Assert.*
import org.junit.Test

class DepthReadbackGateTest {
    @Test fun depthResultRedrawCannotRestartInferenceOnPause() {
        val gate = DepthReadbackGate()
        assertTrue(gate.shouldCapture(true, 0, 33))
        assertFalse(gate.shouldCapture(false, 100, 33))
        assertFalse(gate.shouldCapture(false, 200, 33))
        assertTrue(gate.shouldCapture(true, 201, 33))
    }

    @Test fun skippedFramesAndUiRedrawsDoNotExtendPacingDeadline() {
        val gate = DepthReadbackGate()
        assertTrue(gate.shouldCapture(true, 0, 100))
        assertFalse(gate.shouldCapture(true, 33, 100))
        assertFalse(gate.shouldCapture(false, 99, 100))
        assertTrue(gate.shouldCapture(true, 100, 100))
    }

    @Test fun thermalPauseRequiresFreshFrameAfterRecovery() {
        val gate = DepthReadbackGate()
        assertFalse(gate.shouldCapture(true, 0, Long.MAX_VALUE))
        assertFalse(gate.shouldCapture(false, 100, 33))
        assertTrue(gate.shouldCapture(true, 101, 33))
    }

    @Test fun newSurfaceResetsPacing() {
        val gate = DepthReadbackGate()
        assertTrue(gate.shouldCapture(true, 100, 33))
        gate.reset()
        assertTrue(gate.shouldCapture(true, 101, 33))
    }
}
