package com.rfwatch.core.cs

import com.rfwatch.core.rules.DeviceFacts
import com.rfwatch.core.rules.RuleEngine
import com.rfwatch.core.rules.RulePacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomSweepAndBaselineTest {

    private val engine = RuleEngine(RulePacks.loadBuiltin())

    @Test
    fun hiddenSsidIsFlagged() {
        val findings = RoomSweep.analyze(
            listOf(DeviceFacts(mac = "AA:BB:CC:11:22:33", ssid = null, name = null, hiddenSsid = true, rssi = -70)),
            engine,
        )
        assertTrue(findings.any { it.reason == SweepReason.HIDDEN_SSID })
    }

    @Test
    fun strongSourcelessSignalIsPriority() {
        val findings = RoomSweep.analyze(
            listOf(DeviceFacts(mac = "AA:BB:CC:11:22:34", ssid = null, name = null, rssi = -38)),
            engine,
        )
        val f = findings.firstOrNull { it.reason == SweepReason.STRONG_SOURCELESS }
        assertTrue("expected strong-sourceless finding", f != null)
        assertEquals(com.rfwatch.core.rules.Threat.PRIORITY, f!!.threat)
        assertTrue(f.huntable)
    }

    @Test
    fun hikvisionCameraIsFlaggedAndHuntable() {
        val findings = RoomSweep.analyze(
            listOf(DeviceFacts(mac = "44:19:B6:aa:bb:cc", ssid = "Lobby", name = null, rssi = -60)),
            engine,
        )
        assertTrue(findings.any { it.reason == SweepReason.CAMERA_IOT_SIGNATURE && it.huntable })
    }

    @Test
    fun bleAdvertisedCameraNameIsFlaggedAsCandidate() {
        val findings = RoomSweep.analyze(
            listOf(DeviceFacts(mac = "DA:11:22:33:44:55", ssid = null, name = "Reolink Camera", rssi = -63)),
            engine,
        )
        assertTrue(findings.any { it.reason == SweepReason.CAMERA_IOT_SIGNATURE })
    }

    @Test
    fun baselineMaturesThenFlagsNewTracker() {
        var b: GeofenceBaseline? = null
        repeat(3) { b = Baseline.update(b, "Home", setOf("router", "tv")) }
        assertEquals(3, b!!.sessions)

        val anomalies = Baseline.anomalies(
            baseline = b!!,
            currentDevices = setOf("router", "tv", "airtag-x"),
            priorityDevices = emptySet(),
            trackerDevices = setOf("airtag-x"),
        )
        val hit = anomalies.firstOrNull { it.deviceId == "airtag-x" }
        assertTrue("expected a priority-new anomaly for the tracker", hit != null)
        assertEquals(AnomalyKind.PRIORITY_NEW_DEVICE, hit!!.kind)
    }

    @Test
    fun immatureBaselineRaisesNothing() {
        var b: GeofenceBaseline? = null
        repeat(2) { b = Baseline.update(b, "Home", setOf("router")) }
        assertTrue(Baseline.anomalies(b!!, setOf("router", "stranger"), emptySet(), emptySet()).isEmpty())
    }

    @Test
    fun disappearedRegularIsFlagged() {
        var b: GeofenceBaseline? = null
        repeat(5) { b = Baseline.update(b, "Home", setOf("router", "camera")) }
        // 'camera' was present every session; now it's gone.
        val anomalies = Baseline.anomalies(b!!, setOf("router"), emptySet(), emptySet())
        assertTrue(anomalies.any { it.kind == AnomalyKind.DISAPPEARED_REGULAR && it.deviceId == "camera" })
    }
}
