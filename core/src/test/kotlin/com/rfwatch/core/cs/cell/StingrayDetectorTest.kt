package com.rfwatch.core.cs.cell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StingrayDetectorTest {

    private fun cell(
        radio: RadioType, cid: Long, area: Int, dbm: Int, serving: Boolean,
    ) = CellObservation(
        radio = radio, mcc = 505, mnc = 1, areaCode = area, cellId = cid,
        pci = null, channel = null, signalDbm = dbm, isServing = serving,
        isRegistered = serving, timestampMs = 0,
    )

    private fun normalLteScan() = listOf(
        cell(RadioType.LTE, 100, 5, -85, true),
        cell(RadioType.LTE, 101, 5, -95, false),
        cell(RadioType.LTE, 102, 5, -98, false),
        cell(RadioType.LTE, 103, 5, -101, false),
    )

    @Test
    fun normalNetworkIsNotFlagged() {
        val d = StingrayDetector(learningScans = 2)
        var t = 0L
        repeat(3) { d.analyze(normalLteScan(), t); t += 1000 }
        val a = d.analyze(normalLteScan(), t)
        assertEquals(CellThreatLevel.NONE, a.level)
        assertTrue(a.findings.isEmpty())
    }

    @Test
    fun imsiCatcherLikeCellIsHigh() {
        val d = StingrayDetector(learningScans = 2)
        var t = 0L
        // Learn a normal LTE area with neighbours.
        repeat(3) { d.analyze(normalLteScan(), t); t += 1000 }
        // Then a lone, unknown, implausibly strong 2G cell appears.
        val a = d.analyze(listOf(cell(RadioType.GSM, 999, 5, -40, true)), t)
        assertEquals(CellThreatLevel.HIGH, a.level)
        val types = a.findings.map { it.type }.toSet()
        assertTrue(CellFindingType.DOWNGRADE_2G in types)
        assertTrue(CellFindingType.LONE_CELL in types)
        assertTrue(CellFindingType.SIGNAL_ANOMALY in types)
        assertTrue(CellFindingType.UNKNOWN_CELL in types)
    }

    @Test
    fun areaCodeChangeForKnownCellIsFlagged() {
        val d = StingrayDetector(learningScans = 1)
        var t = 0L
        d.analyze(normalLteScan(), t); t += 1000           // learn cell 100 @ area 5
        d.analyze(normalLteScan(), t); t += 1000           // past learning
        val a = d.analyze(
            listOf(
                cell(RadioType.LTE, 100, 7, -85, true),      // same cell id, new area code
                cell(RadioType.LTE, 101, 7, -95, false),
            ),
            t,
        )
        assertTrue(a.findings.any { it.type == CellFindingType.AREA_INCONSISTENT })
        assertTrue(a.level.ordinal >= CellThreatLevel.MEDIUM.ordinal)
    }

    @Test
    fun staysQuietDuringLearningWindow() {
        val d = StingrayDetector(learningScans = 5)
        // Even a weird cell during learning should not alert (we don't know the area yet).
        val a = d.analyze(listOf(cell(RadioType.GSM, 777, 9, -40, true)), 0)
        assertEquals(CellThreatLevel.NONE, a.level)
        assertTrue(a.learning)
    }
}
