package com.rfwatch.core.cs

import com.rfwatch.core.rules.DeviceFacts
import com.rfwatch.core.rules.RuleEngine
import com.rfwatch.core.rules.RulePacks
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AttackDetectorsTest {

    @Test fun bleSpamBurstFromRotatingAddressesIsDetected() {
        val d = BleSpamDetector()
        var hit: BleSpamDetection? = null
        for (i in 0 until 20) {
            hit = hit ?: d.onPopupAdvert("C0:00:00:00:00:%02X".format(i), if (i % 2 == 0) "Apple proximity pairing" else "Google Fast Pair", -55, i * 300L)
        }
        assertNotNull(hit)
        assertTrue(hit!!.distinctAddresses >= 8)
        assertTrue(hit.describe().contains("pairing pop-up"))
    }

    @Test fun oneRealAirPodsCaseIsNotSpam() {
        val d = BleSpamDetector()
        repeat(100) { assertNull(d.onPopupAdvert("AA:BB:CC:DD:EE:FF", "Apple proximity pairing", -50, it * 100L)) }
    }

    @Test fun popupTypeRecognisesPayloads() {
        val apple = AdvParser.fromRawAdvBytes(AdvParser.hex("1BFF4C00 0719 010E20 000000000000000000000000000000000000000000"), "x", -50, 0)
        assertEquals("Apple proximity pairing", BleSpamDetector.popupType(apple))
        val fp = AdvParser.fromRawAdvBytes(AdvParser.hex("06162CFE123456"), "x", -50, 0)
        assertEquals("Google Fast Pair", BleSpamDetector.popupType(fp))
    }

    @Test fun evilTwinWithOpenSecurityIsFlagged() {
        val t = EvilTwinDetector()
        t.learn("HomeNet", "3C:2E:F9:00:11:22", "WPA2", 0)
        val det = t.observe("HomeNet", "02:13:37:AA:BB:CC", "Open", 20 * 60_000L)
        assertNotNull(det)
        assertTrue(det!!.reasons.any { it.startsWith("security dropped") })
        assertTrue(det.describe().contains("Don't join it"))
    }

    @Test fun meshNodeFromSameVendorIsNotFlagged() {
        val t = EvilTwinDetector()
        t.learn("HomeNet", "3C:2E:F9:00:11:22", "WPA2", 0)
        assertNull(t.observe("HomeNet", "3C:2E:F9:00:11:33", "WPA2", 20 * 60_000L))
    }

    @Test fun routerDerivedVirtualBssidIsNotFlagged() {
        // Real false-alarm pattern (2026-11-01, addresses changed): a Telstra (Vantiva) modem's second radio / virtual AP sets the
        // locally-administered bit (d4 → d6) and bumps the last byte (b9 → c1). Same SSID, same WPA2.
        val t = EvilTwinDetector()
        t.learn("Telstra0010B9", "d4:35:1d:00:10:b9", "WPA2", 0)
        assertNull(t.observe("Telstra0010B9", "d6:35:1d:00:10:c1", "WPA2", 20 * 60_000L))
    }

    @Test fun routerDerivedBssidThatDropsSecurityIsStillFlagged() {
        val t = EvilTwinDetector()
        t.learn("Telstra0010B9", "d4:35:1d:00:10:b9", "WPA2", 0)
        val det = t.observe("Telstra0010B9", "d6:35:1d:00:10:c1", "Open", 20 * 60_000L)
        assertNotNull(det)
        assertTrue(det!!.reasons.any { it.startsWith("security dropped") })
    }

    @Test fun unrelatedRandomisedBssidIsStillFlagged() {
        val t = EvilTwinDetector()
        t.learn("Telstra0010B9", "d4:35:1d:00:10:b9", "WPA2", 0)
        val det = t.observe("Telstra0010B9", "da:a1:19:42:0e:77", "WPA2", 20 * 60_000L)
        assertNotNull(det)
        assertTrue(det!!.reasons.any { it.contains("randomised address") })
    }

    @Test fun derivedBssidMatching() {
        assertTrue(EvilTwinDetector.derivedFrom("d6:35:1d:00:10:c1", "d4:35:1d:00:10:b9"))
        assertTrue(EvilTwinDetector.derivedFrom("3e:2e:f9:00:11:20", "3C:2E:F9:00:11:22"))
        assertEquals(false, EvilTwinDetector.derivedFrom("d6:35:1d:00:11:c1", "d4:35:1d:00:10:b9")) // tail 0x100+ apart
        assertEquals(false, EvilTwinDetector.derivedFrom("d6:99:1d:00:10:c1", "d4:35:1d:00:10:b9")) // octet 2 differs
        assertEquals(false, EvilTwinDetector.derivedFrom("d5:35:1d:00:10:c1", "d4:35:1d:00:10:b9")) // multicast bit
    }

    @Test fun attackToolRules() {
        val engine = RuleEngine(RulePacks.loadBuiltin())
        fun tool(f: DeviceFacts) = engine.evaluate(f).firstOrNull { it.packId == "attack_tools" }?.ruleId
        assertEquals("flipper_zero", tool(DeviceFacts(null, null, "Flipper El1a7te")))
        assertEquals("flipper_zero", tool(DeviceFacts(null, null, null, companyIds = setOf(0x0E29))))
        assertEquals("esp32_attack_firmware", tool(DeviceFacts("02:11:22:33:44:55", "pwned", null)))
        assertEquals("wifi_pineapple", tool(DeviceFacts("00:13:37:11:22:33", "Pineapple_ABCD", null)))
        assertEquals("hardware_implant", tool(DeviceFacts("02:11:22:33:44:55", "O.MG", null)))
        assertEquals(null, tool(DeviceFacts("00:13:37:11:22:33", "HomeNet", null)))
    }
}
