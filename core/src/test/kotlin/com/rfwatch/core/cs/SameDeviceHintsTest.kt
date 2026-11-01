package com.rfwatch.core.cs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SameDeviceHintsTest {

    private val min = 60_000L

    private fun ble(id: String, first: Long, last: Long, rssi: Int, brand: String? = "Apple", kind: String? = "PHONE", name: String? = null) =
        DeviceSummary(id, "BLE", id, name, brand, null, kind, first * min, last * min, rssi)

    private fun wifi(mac: String, ssid: String?) = DeviceSummary(mac, "WIFI", mac, ssid, null, null, "ACCESS_POINT", 0, 10 * min, -60)

    @Test fun rotationChainIsLinkedBothWays() {
        val a = ble("4A:00:00:00:00:01", 0, 15, -60)
        val b = ble("5B:00:00:00:00:02", 15, 30, -62)
        val all = listOf(a, b)
        val fwd = SameDeviceHints.forDevice(a, all) { if (it == b.id) -61 else null }
        assertEquals(listOf(b.id), fwd.map { it.otherId })
        assertEquals(HintStrength.PROBABLY, fwd.single().strength)
        assertTrue(fwd.single().reason.contains("next rotating address"))
        val back = SameDeviceHints.forDevice(b, all) { if (it == b.id) -61 else null }
        assertEquals(listOf(a.id), back.map { it.otherId })
        assertTrue(back.single().reason.contains("previous rotating address"))
    }

    @Test fun overlappingLifetimesAreTwoDevices() {
        val a = ble("4A:00:00:00:00:01", 0, 20, -60)
        val b = ble("5B:00:00:00:00:02", 5, 30, -60)
        assertTrue(SameDeviceHints.forDevice(a, listOf(a, b)) { -60 }.isEmpty())
    }

    @Test fun bigSignalJumpIsNotLinked() {
        val a = ble("4A:00:00:00:00:01", 0, 15, -50)
        val b = ble("5B:00:00:00:00:02", 16, 30, -80)
        assertTrue(SameDeviceHints.forDevice(a, listOf(a, b)) { -80 }.isEmpty())
    }

    @Test fun longGapIsNotLinked() {
        val a = ble("4A:00:00:00:00:01", 0, 15, -60)
        val b = ble("5B:00:00:00:00:02", 25, 30, -60)
        assertTrue(SameDeviceHints.forDevice(a, listOf(a, b)) { -60 }.isEmpty())
    }

    @Test fun unknownSignatureIsNeverLinked() {
        val a = ble("4A:00:00:00:00:01", 0, 15, -60, brand = null, kind = "UNKNOWN")
        val b = ble("5B:00:00:00:00:02", 15, 30, -60, brand = null, kind = "UNKNOWN")
        assertTrue(SameDeviceHints.forDevice(a, listOf(a, b)) { -60 }.isEmpty())
    }

    @Test fun differentSignatureIsNotLinked() {
        val a = ble("4A:00:00:00:00:01", 0, 15, -60)
        val b = ble("5B:00:00:00:00:02", 15, 30, -60, kind = "WATCH")
        assertTrue(SameDeviceHints.forDevice(a, listOf(a, b)) { -60 }.isEmpty())
    }

    @Test fun ambiguousChainShowsNothing() {
        val a = ble("4A:00:00:00:00:01", 0, 15, -60)
        val b = ble("5B:00:00:00:00:02", 15, 30, -61)
        val c = ble("6E:00:00:00:00:03", 16, 30, -59)
        assertTrue(SameDeviceHints.forDevice(a, listOf(a, b, c)) { null }.isEmpty())
    }

    @Test fun notMutualShowsNothing() {
        // b's only successor-of-a, but b has two possible predecessors (a and x) → ambiguous.
        val a = ble("4A:00:00:00:00:01", 0, 15, -60)
        val x = ble("4E:00:00:00:00:09", 1, 15, -61)
        val b = ble("5B:00:00:00:00:02", 15, 30, -60)
        assertTrue(SameDeviceHints.forDevice(a, listOf(a, x, b)) { null }.isEmpty())
    }

    @Test fun genericSignatureNeedsTightGap() {
        val a = ble("4A:00:00:00:00:01", 0, 15, -60)
        val b = ble("5B:00:00:00:00:02", 19, 30, -60) // 4 min gap: ok for named devices, too loose for "Apple · phone"
        assertTrue(SameDeviceHints.forDevice(a, listOf(a, b)) { -60 }.isEmpty())
        val an = ble("4A:00:00:00:00:01", 0, 15, -60, name = "Jo's Watch")
        val bn = ble("5B:00:00:00:00:02", 19, 30, -60, name = "Jo's Watch")
        assertEquals(1, SameDeviceHints.forDevice(an, listOf(an, bn)) { -60 }.size)
    }

    @Test fun publicAddressesAreNotRotations() {
        val a = ble("00:1A:7D:00:00:01", 0, 15, -60)
        val b = ble("00:1A:7D:00:00:02", 15, 30, -60)
        assertTrue(SameDeviceHints.forDevice(a, listOf(a, b)) { -60 }.isEmpty())
    }

    @Test fun dualBandRouterIsLinked() {
        val a = wifi("d4:35:1d:00:10:b9", "Telstra0010B9")
        val b = wifi("d6:35:1d:00:10:c1", "Telstra0010B9")
        val c = wifi("d4:35:1d:00:10:ba", "Telstra0010B9-5G")
        val g = wifi("d4:35:1d:00:10:bb", "Telstra Air")
        val far = wifi("d4:35:1d:00:90:00", "Telstra0010B9")
        val hints = SameDeviceHints.forDevice(a, listOf(a, b, c, g, far)) { null }.associateBy { it.otherId }
        assertEquals(HintStrength.PROBABLY, hints[b.id]?.strength)
        assertEquals(HintStrength.PROBABLY, hints[c.id]?.strength)
        assertEquals(HintStrength.POSSIBLY, hints[g.id]?.strength)
        assertTrue(far.id !in hints)
    }
}
