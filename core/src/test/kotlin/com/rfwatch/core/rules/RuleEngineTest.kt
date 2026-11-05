package com.rfwatch.core.rules

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RuleEngineTest {

    private val engine = RuleEngine(RulePacks.loadBuiltin())

    @Test
    fun loadsAllFourBuiltinPacks() {
        val packs = RulePacks.loadBuiltin()
        assertEquals(7, packs.size)
        assertEquals(setOf("law_enforcement", "trackers", "cameras_iot", "fleet", "emergency_services", "attack_tools", "drones"),
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
    fun appleCompanyIdAloneIsNotTracker() {
        val hits = engine.evaluate(
            DeviceFacts(mac = null, ssid = null, name = null, companyIds = setOf(76))
        )
        assertTrue("Apple company ID is shared by ordinary Apple devices: $hits", hits.none { it.category == RuleCategory.TRACKER })
    }

    @Test
    fun appleFindMyServiceUuidMatchesTracker() {
        val hits = engine.evaluate(
            DeviceFacts(mac = null, ssid = null, name = null, serviceUuids = setOf(0xFD44))
        )
        assertTrue(hits.any { it.category == RuleCategory.TRACKER && it.ruleId == "apple_findmy" })
    }

    @Test
    fun sharedEddystoneUuidAloneIsNotGoogleTracker() {
        val hits = engine.evaluate(
            DeviceFacts(mac = null, ssid = null, name = null, serviceUuids = setOf(0xFEAA))
        )
        assertTrue("FEAA is also used by ordinary Eddystone beacons: $hits", hits.none { it.category == RuleCategory.TRACKER })
    }

    @Test
    fun ordinaryDeviceHasNoHits() {
        val hits = engine.evaluate(DeviceFacts(mac = "AA:BB:CC:00:00:00", ssid = "MyHomeWiFi", name = "Pixel"))
        assertTrue(hits.isEmpty())
    }

    @Test
    fun droneSignaturesMatchOuiSsidAndCompany() {
        val oui = engine.evaluate(DeviceFacts(mac = "60:60:1F:12:34:56", ssid = "home", name = null))
        assertTrue("DJI OUI, got $oui", oui.any { it.category == RuleCategory.DRONE && it.ruleId == "dji" })
        val ssid = engine.evaluate(DeviceFacts(mac = "02:11:22:33:44:55", ssid = "WIFI-UAV-1A2B3C", name = null))
        assertTrue("cheap FPV SSID, got $ssid", ssid.any { it.ruleId == "generic_wifi_fpv" })
        val ble = engine.evaluate(DeviceFacts(mac = "C2:11:22:33:44:55", ssid = null, name = null, companyIds = setOf(0x08AA)))
        assertTrue("DJI SIG company, got $ble", ble.any { it.ruleId == "dji" })
        // DJI Osmo (camera) block is deliberately not a drone signature.
        assertTrue(engine.evaluate(DeviceFacts(mac = "20:1F:55:12:34:56", ssid = null, name = null)).none { it.category == RuleCategory.DRONE })
        val trailer = engine.evaluate(DeviceFacts(mac = "02:11:22:33:44:55", ssid = "LVT-Unit-0042", name = null))
        assertTrue("surveillance trailer, got $trailer", trailer.any { it.ruleId == "surveillance_trailer" })
    }

    @Test
    fun droneTrailerIsGroundStationNotToyDrone() {
        val hits = engine.evaluate(DeviceFacts(mac = "86:11:22:33:44:55", ssid = "Drone Trailer 7", name = null))
        assertTrue("ground station, got $hits", hits.any { it.ruleId == "drone_ground_station" })
        assertTrue("not a toy FPV drone, got $hits", hits.none { it.ruleId == "generic_wifi_fpv" })
        assertTrue(engine.evaluate(DeviceFacts(mac = "02:11:22:33:44:55", ssid = "DRONE-1A2B3C", name = null)).any { it.ruleId == "generic_wifi_fpv" })
    }
}
