package com.rfwatch.core.cs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnDeviceLearnerTest {

    @Test
    fun learnsConsistentlyPresentDevice() {
        val now = 100L * 24 * 60 * 60 * 1000
        val day = 24 * 60 * 60 * 1000L
        // 10 sessions in the last 7 days; "watch" in 9, "stranger" in 2.
        val sessions = (0 until 10).map { i ->
            val ids = mutableSetOf<String>()
            if (i != 3) ids.add("watch")
            if (i == 1 || i == 7) ids.add("stranger")
            SessionPresence(startMs = now - i * (day / 2), deviceIds = ids)
        }
        val candidates = OwnDeviceLearner.learn(sessions, now)
        assertEquals(1, candidates.size)
        assertEquals("watch", candidates[0].deviceId)
        assertTrue(candidates[0].presenceRatio >= OwnDeviceLearner.PRESENCE_THRESHOLD)
    }

    @Test
    fun ignoresSessionsOutsideWindow() {
        val now = 100L * 24 * 60 * 60 * 1000
        val day = 24 * 60 * 60 * 1000L
        val old = (0 until 10).map { SessionPresence(now - (30 + it) * day, setOf("watch")) }
        assertEquals(0, OwnDeviceLearner.learn(old, now).size)
    }
}
