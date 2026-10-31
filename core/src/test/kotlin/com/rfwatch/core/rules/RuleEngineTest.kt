package com.rfwatch.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private val engine = RuleEngine(RulePacks.loadBuiltin())

    @Test
    fun loadsAllFourBuiltinPacks() {
        val packs = RulePacks.loadBuiltin()
        assertEquals(4, packs.size)
        assertEquals(setOf("law_enforcement", "trackers", "cameras_iot", "fleet"),
            packs.map { it.id }.toSet())
    }

    @Test
    fun axonOuiIsPriorityLawEnforcement() {
        val hits = engine.evaluate(DeviceFacts(mac = "00:25:DF:AB:CD:EF", ssid = null, name = null))
        val le = hits.firstOrNull { it.category == RuleCategory.LAW_ENFORCEMENT }
        assertTrue("expected Axon LE hit, got $hits", le != null)
        assertEquals(Threat.PRIORITY, le!!.threat)
    }

    @Test
    fun axonCompanyIdMatchesBleLiveSignature() {
        val hits = engine.evaluate(DeviceFacts(mac = "C2:11:22:33:44:55", ssid = null, name = null, companyIds = setOf(0xFC81)))
        assertTrue("expected Axon BLE manufacturer hit, got $hits", hits.any { it.ruleId == "axon" })
    }

    @Test
    fun axonSsidMatchesWifiSignature() {
        val hits = engine.evaluate(DeviceFacts(mac = "02:11:22:33:44:55", ssid = "AXON-CAMERA-1234", name = null))
        assertTrue("expected Axon SSID hit, got $hits", hits.any { it.ruleId == "axon" })
    }

    @Test
    fun hikvisionOuiIsCamera() {
        val hits = engine.evaluate(DeviceFacts(mac = "44:19:B6:00:00:01", ssid = null, name = null))
        assertTrue(hits.any { it.category == RuleCategory.CAMERA_IOT && it.threat == Threat.PRIORITY })
    }

    @Test
    fun tuyaSsidMatches() {
        val hits = engine.evaluate(DeviceFacts(mac = null, ssid = "SmartLife-A1B2", name = null))
        assertTrue(hits.any { it.ruleId == "tuya_smartlife" })
    }

    @Test
    fun appleCompanyIdIsTracker() {
        val hits = engine.evaluate(
            DeviceFacts(mac = null, ssid = null, name = null, companyIds = setOf(76))
        )
        assertTrue(hits.any { it.category == RuleCategory.TRACKER })
    }

    @Test
    fun ordinaryDeviceHasNoHits() {
        val hits = engine.evaluate(DeviceFacts(mac = "AA:BB:CC:00:00:00", ssid = "MyHomeWiFi", name = "Pixel"))
        assertTrue(hits.isEmpty())
    }
}
