package com.rfwatch.core.cs

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrackerAlertGateTest {
    @Test
    fun `requires repeated persistent sightings and limits global alert rate`() {
        val gate = TrackerAlertGate()
        assertFalse(gate.shouldAlert("candidate-a", 1_000_000L, 0.9))
        assertFalse(gate.shouldAlert("candidate-a", 1_030_000L, 0.9))
        assertTrue(gate.shouldAlert("candidate-a", 1_061_000L, 0.9))
        assertFalse(gate.shouldAlert("candidate-b", 1_100_000L, 0.9))
        assertFalse(gate.shouldAlert("candidate-b", 1_130_000L, 0.9))
        assertFalse(gate.shouldAlert("candidate-b", 1_160_000L, 0.9))
        assertFalse(gate.shouldAlert("candidate-b", 3_000_000L, 0.9))
        assertFalse(gate.shouldAlert("candidate-b", 3_030_000L, 0.9))
        assertTrue(gate.shouldAlert("candidate-b", 3_061_000L, 0.9))
    }

    @Test
    fun `weak matches and brief sightings do not alert`() {
        val gate = TrackerAlertGate()
        repeat(4) { index ->
            assertFalse(gate.shouldAlert("candidate", 2_000_000L + index * 30_000L, 0.79))
        }
        assertFalse(gate.shouldAlert("candidate", 2_000_000L, 0.9))
        assertFalse(gate.shouldAlert("candidate", 2_030_000L, 0.9))
        assertFalse(gate.shouldAlert("candidate", 2_059_000L, 0.9))
    }
}
