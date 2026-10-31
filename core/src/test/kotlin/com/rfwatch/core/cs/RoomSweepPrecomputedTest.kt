package com.rfwatch.core.cs

import com.rfwatch.core.math.Hex
import com.rfwatch.core.rules.DeviceFacts
import com.rfwatch.core.rules.RuleEngine
import com.rfwatch.core.rules.RulePacks
import org.junit.Assert.assertEquals
import org.junit.Test

class RoomSweepPrecomputedTest {
    private val engine = RuleEngine(RulePacks.loadBuiltin())

    @Test fun precomputedHitsGiveSameFindingsAsRuleEngine() {
        val samples = listOf(
            DeviceFacts(mac = "AA:BB:CC:11:22:33", ssid = null, name = null, hiddenSsid = true, rssi = -70),
            DeviceFacts(mac = "AA:BB:CC:11:22:34", ssid = null, name = null, rssi = -38),
            DeviceFacts(mac = "AA:BB:CC:11:22:35", ssid = "Wyze Cam", name = null, rssi = -60),
            DeviceFacts(mac = "AA:BB:CC:11:22:36", ssid = "HomeNet", name = null, rssi = -80),
        )
        for (d in samples) {
            val viaEngine = RoomSweep.analyze(listOf(d), engine).map { it.reason to it.threat }.toSet()
            val viaHits = RoomSweep.analyze(d, engine.evaluate(d)).map { it.reason to it.threat }.toSet()
            assertEquals(d.ssid ?: d.mac, viaEngine, viaHits)
        }
    }

    @Test fun hexMatchesStringFormat() {
        val bytes = byteArrayOf(0, 1, 0x0F, 0x10, 0x7F, -128, -1, 0x4C)
        assertEquals(bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }, Hex.upper(bytes))
        assertEquals("", Hex.upper(ByteArray(0)))
    }
}
