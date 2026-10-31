package com.rfwatch.core.cs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SkimmerScorerTest {

    @Test fun classicHc05AtThePumpIsHigh() {
        val a = SkimmerScorer.score("98:D3:31:F5:12:34", "HC-05", 0x001F00, -58, classicOnly = true)
        assertEquals(SkimmerLevel.HIGH, a.level)
        assertTrue(a.reasons.any { it.contains("Bolutek") })
        assertTrue(a.reasons.any { it.contains("uncategorised") })
    }

    @Test fun cloneModuleWithDefaultNameIsAtLeastMedium() {
        val a = SkimmerScorer.score("20:16:04:11:22:33", "HC-06", null, -75, classicOnly = true)
        assertTrue(a.level != SkimmerLevel.LOW)
    }

    @Test fun phoneOrHeadphonesAreLow() {
        assertEquals(SkimmerLevel.LOW, SkimmerScorer.score("3C:2E:F9:11:22:33", "Jo's iPhone", 0x5A020C, -55, false).level)
        assertEquals(SkimmerLevel.LOW, SkimmerScorer.score("00:1B:66:11:22:33", "WH-1000XM4", 0x240404, -55, true).level)
    }

    @Test fun obdDongleWithModuleClassIsNotHigh() {
        val a = SkimmerScorer.score("00:1D:A5:11:22:33", "OBDII", 0x001F00, -60, true)
        assertTrue(a.level != SkimmerLevel.HIGH)
    }
}
