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
        assertEquals("axon", ruleFor(DeviceFacts(null, null, null, companyIds = setOf(0x034D))))
        assertEquals("axon", ruleFor(DeviceFacts(null, null, null, serviceUuids = setOf(0xFE6C))))
        assertEquals("motorola_solutions", ruleFor(DeviceFacts(null, null, null, companyIds = setOf(0x04EC))))
        assertEquals("alpr", ruleFor(DeviceFacts("B4:1E:52:00:11:22", null, null)))
        assertEquals("watchguard_video", ruleFor(DeviceFacts("00:1D:96:00:11:22", null, null)))
        assertEquals("vehicle_modem", ruleFor(DeviceFacts("00:A0:D5:00:11:22", null, null)))
        // WatchGuard *Technologies* firewalls are a different company and must not match.
        assertEquals(null, ruleFor(DeviceFacts("00:90:7F:00:11:22", null, null)))
    }
}
