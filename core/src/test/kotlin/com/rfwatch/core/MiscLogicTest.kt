package com.rfwatch.core

import com.rfwatch.core.alerts.AlertPhrasing
import com.rfwatch.core.alerts.AlertType
import com.rfwatch.core.data.RetentionPolicy
import com.rfwatch.core.df.HuntTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MiscLogicTest {

    @Test
    fun alertSpeaksDirectionOnlyWhenConfident() {
        // Spec example: "Priority device approaching, rear left" at relative bearing 225°.
        assertEquals(
            "Priority device approaching, rear left",
            AlertPhrasing.phrase(AlertType.PRIORITY_APPROACHING, 225.0, confidence = 0.7),
        )
        // Below the confidence gate, no direction is spoken.
        assertEquals(
            "Priority device approaching",
            AlertPhrasing.phrase(AlertType.PRIORITY_APPROACHING, 225.0, confidence = 0.5),
        )
    }

    @Test
    fun relativeSectorsMapCorrectly() {
        assertEquals("ahead", AlertPhrasing.relativeSector(0.0))
        assertEquals("right", AlertPhrasing.relativeSector(90.0))
        assertEquals("behind", AlertPhrasing.relativeSector(180.0))
        assertEquals("left", AlertPhrasing.relativeSector(270.0))
        assertEquals("ahead left", AlertPhrasing.relativeSector(315.0))
    }

    @Test
    fun huntTonePitchRisesWithSignal() {
        assertTrue(HuntTone.pitchHz(-40.0) > HuntTone.pitchHz(-90.0))
        assertEquals(HuntTone.Proximity.HOTTER, HuntTone.proximity(5.0))
        assertEquals(HuntTone.Proximity.COLDER, HuntTone.proximity(-5.0))
        assertEquals(HuntTone.Proximity.SAME, HuntTone.proximity(0.5))
    }

    @Test
    fun retentionPurgesOldRecords() {
        val now = 100L * 24 * 60 * 60 * 1000
        val day = 24 * 60 * 60 * 1000L
        val items = listOf(now - 5 * day, now - 29 * day, now - 31 * day, now - 100 * day)
        val kept = RetentionPolicy.purge(items, now, 30) { it }
        assertEquals(listOf(now - 5 * day, now - 29 * day), kept)
        assertEquals(2, RetentionPolicy.purgeCount(items, now, 30) { it })
    }
}
