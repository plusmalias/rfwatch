package com.rfwatch.core.alerts

import com.rfwatch.core.rules.DeviceFacts
import com.rfwatch.core.rules.RuleEngine
import com.rfwatch.core.rules.RulePacks
import com.rfwatch.core.rules.Threat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LawEncounterTrackerTest {

    @Test fun onePoliceCarIsOneLoudAlertThenSilentUpdates() {
        val t = LawEncounterTracker()
        assertEquals(LawAction.ALERT, t.onHit("router", "Agency vehicle router", Threat.NOTABLE, -70, 0))
        assertEquals(LawAction.UPDATE, t.onHit("radio", "Tait radio", Threat.NOTABLE, -75, 1_000))
        assertEquals(LawAction.NONE, t.onHit("radio", "Tait radio", Threat.NOTABLE, -74, 2_000))
        assertEquals(LawAction.UPDATE, t.onHit("radio", "Tait radio", Threat.NOTABLE, -73, 7_000))
        assertEquals(2, t.snapshot()!!.signatures.size)
    }

    @Test fun firstPriorityDeviceInAnEncounterReAlertsAfterCooldown() {
        val t = LawEncounterTracker()
        t.onHit("router", "router", Threat.NOTABLE, -70, 0)
        // Axon camera appears 30 s later — inside the cooldown, so only an update.
        assertEquals(LawAction.UPDATE, t.onHit("cam1", "Axon", Threat.PRIORITY, -60, 30_000))
        val t2 = LawEncounterTracker()
        t2.onHit("router", "router", Threat.NOTABLE, -70, 0)
        assertEquals(LawAction.ALERT, t2.onHit("cam1", "Axon", Threat.PRIORITY, -60, 90_000))
        // A second camera is not an escalation.
        assertEquals(LawAction.UPDATE, t2.onHit("cam2", "Axon", Threat.PRIORITY, -65, 200_000))
    }

    @Test fun encounterEndsAfterQuietPeriodAndNextHitAlertsAgain() {
        val t = LawEncounterTracker(quietEndMs = 120_000)
        t.onHit("cam", "Axon", Threat.PRIORITY, -60, 0)
        assertTrue(t.tick(60_000) != LawAction.ENDED)
        assertEquals(LawAction.ENDED, t.tick(121_000))
        assertFalse(t.snapshot()!!.active)
        assertEquals(LawAction.NONE, t.tick(130_000))
        assertEquals(LawAction.ALERT, t.onHit("cam", "Axon", Threat.PRIORITY, -60, 400_000))
    }

    @Test fun lawPackMatchesRegistryIdentifiers() {
        val engine = RuleEngine(RulePacks.loadBuiltin())
        fun ruleFor(f: DeviceFacts) = engine.evaluate(f).firstOrNull { it.packId == "law_enforcement" }?.ruleId
        assertEquals("taser", ruleFor(DeviceFacts(null, null, null, companyIds = setOf(0x034D))))
        assertEquals("taser", ruleFor(DeviceFacts(null, null, null, serviceUuids = setOf(0xFE6C))))
        assertEquals("taser", ruleFor(DeviceFacts(null, null, "TASER 10")))
        assertEquals("axon", ruleFor(DeviceFacts("00:25:DF:11:22:33", null, null)))
        assertEquals("traffic_enforcement", ruleFor(DeviceFacts("00:30:7E:11:22:33", null, null)))
        assertEquals("motorola_solutions", ruleFor(DeviceFacts(null, null, null, companyIds = setOf(0x04EC))))
        assertEquals("alpr", ruleFor(DeviceFacts("B4:1E:52:00:11:22", null, null)))
        assertEquals("watchguard_video", ruleFor(DeviceFacts("00:1D:96:00:11:22", null, null)))
        assertEquals("vehicle_modem", ruleFor(DeviceFacts("00:A0:D5:00:11:22", null, null)))
        // WatchGuard *Technologies* firewalls are a different company and must not match.
        assertEquals(null, ruleFor(DeviceFacts("00:90:7F:00:11:22", null, null)))
    }

    @Test fun cameraPackV2CoversBrandsChipsAndSpyCamHotspots() {
        val engine = RuleEngine(RulePacks.loadBuiltin())
        fun cam(f: DeviceFacts) = engine.evaluate(f).firstOrNull { it.packId == "cameras_iot" }?.ruleId
        assertEquals("ezviz", cam(DeviceFacts("0C:A6:4C:11:22:33", null, null)))
        assertEquals("security_camera_brands", cam(DeviceFacts("EC:71:DB:11:22:33", null, null))) // Reolink
        assertEquals("camera_soc", cam(DeviceFacts("D0:31:10:11:22:33", null, null))) // Ingenic Semiconductor
        assertEquals(null, cam(DeviceFacts("00:03:81:11:22:33", null, null))) // Ingenico payment terminal
        assertTrue(cam(DeviceFacts("02:11:22:33:44:55", "MV12345678", null)).orEmpty().startsWith("spycam"))
        assertEquals("spycam_app_ssid", cam(DeviceFacts("02:11:22:33:44:55", "YCC365-ab12", null)))
    }

    @Test fun emergencyServicesPackMatchesEmsAndFireGear() {
        val engine = RuleEngine(RulePacks.loadBuiltin())
        fun ems(f: DeviceFacts) = engine.evaluate(f).firstOrNull { it.packId == "emergency_services" }?.ruleId
        assertEquals("ems_defib_monitor", ems(DeviceFacts(null, null, null, serviceUuids = setOf(0xFE22)))) // ZOLL
        assertEquals("ems_defib_monitor", ems(DeviceFacts("40:BD:9E:11:22:33", null, null))) // Physio-Control
        assertEquals("ems_stryker", ems(DeviceFacts(null, null, null, companyIds = setOf(0x0A5F))))
        assertEquals("fire_scba", ems(DeviceFacts(null, null, null, companyIds = setOf(0x01A4)))) // MSA
        assertEquals("fire_apparatus", ems(DeviceFacts("80:84:A9:11:22:33", null, null))) // Oshkosh / Pierce
        assertEquals(null, ems(DeviceFacts("00:08:BE:11:22:33", null, null))) // 'MSA Group' optics, not MSA Safety
    }
}
