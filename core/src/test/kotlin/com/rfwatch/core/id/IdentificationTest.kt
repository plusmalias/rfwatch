package com.rfwatch.core.id

import com.rfwatch.core.cs.AdvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class IdentificationTest {

    private fun adv(hex: String) = AdvParser.fromRawAdvBytes(AdvParser.hex(hex), "AA:BB:CC:DD:EE:FF", -60, 0)

    @Test fun vendorDbResolvesCompanyAndOui() {
        assertEquals("Apple", VendorDb.company(0x004C))
        assertEquals("Apple", VendorDb.ouiVendor("3C:2E:F9:00:11:22"))
        assertNull("locally administered addresses carry no vendor", VendorDb.ouiVendor("02:2E:F9:00:11:22"))
        assertEquals("Samsung", VendorDb.shortName("Samsung Electronics Co. Ltd."))
        assertEquals("Gree Electric Appliances", VendorDb.shortName("Gree Electric Appliances, Inc. Of Zhuhai"))
    }

    @Test fun airPodsProFromProximityPairing() {
        // flags, then manufacturer data: 0x004C, type 0x07 len 0x19, prefix 0x01, model 0x0E20.
        val fp = BleFingerprinter.fingerprint(adv("02011A 1BFF4C00 0719 010E20 000000000000000000000000000000000000000000"), null)
        assertEquals(DeviceKind.AUDIO, fp.kind)
        assertEquals("AirPods Pro", fp.model)
    }

    @Test fun microsoftCdpWindowsLaptop() {
        val fp = BleFingerprinter.fingerprint(adv("1EFF0600 01 0F 2002 00000000000000000000000000000000000000000000"), null)
        assertEquals(DeviceKind.COMPUTER, fp.kind)
        assertEquals("Windows laptop", fp.model)
    }

    @Test fun appearanceWatchAndFastPair() {
        assertEquals(DeviceKind.WEARABLE, BleFingerprinter.fingerprint(adv("0319C100"), null).kind)
        assertEquals(DeviceKind.AUDIO, BleFingerprinter.fingerprint(adv("06162CFE123456"), null).kind)
    }

    @Test fun nameAndCompanyFallbacks() {
        assertEquals(DeviceKind.AUDIO, BleFingerprinter.fingerprint(adv("0201060AFF8700"), "WH-1000XM5").kind)
        assertEquals(DeviceKind.WEARABLE, BleFingerprinter.fingerprint(adv("05FF870001 02"), null).kind) // Garmin company id
        assertEquals(DeviceKind.UNKNOWN, BleFingerprinter.fingerprint(adv("020106"), null).kind)
    }

    @Test fun wifiHotspotDirectAndSecurity() {
        assertEquals(DeviceKind.HOTSPOT, WifiFingerprinter.fingerprint("Jo's iPhone", "06:11:22:33:44:55").kind)
        assertEquals(DeviceKind.NETWORK, WifiFingerprinter.fingerprint("HomeNet", "3C:2E:F9:00:11:22").kind)
        assertTrue(WifiFingerprinter.fingerprint("DIRECT-4F-HP OfficeJet", "06:11:22:33:44:55").model!!.contains("Direct"))
        assertEquals(WifiSecurity.WPA2_WPA3, WifiFingerprinter.security("[RSN-PSK+SAE-CCMP][ESS]"))
        assertEquals(WifiSecurity.OPEN, WifiFingerprinter.security("[ESS]"))
        assertEquals("5 GHz", WifiFingerprinter.band(5180))
    }

    @Test fun deviceDbTablesLoadAndDecode() {
        assertTrue(DeviceDb.fastPairModel(0x000006)?.name.orEmpty().contains("Pixel", ignoreCase = true))
        assertEquals("Watch", DeviceDb.appearance(0x00C1)?.category)
        assertEquals("Sports Watch", DeviceDb.appearance(0x00C1)?.subcategory)
        assertTrue(DeviceDb.uuid16(0xFD5A)?.name.orEmpty().startsWith("Samsung"))
        // Fast Pair model in service data resolves to a named product.
        val fp = BleFingerprinter.fingerprint(adv("06162CFE000006"), null)
        assertTrue(fp.model.orEmpty().contains("Pixel", ignoreCase = true))
        // Appearance subcategory is carried into the model label.
        assertEquals("Watch · Sports Watch", BleFingerprinter.fingerprint(adv("0319C100"), null).model)
    }

    @Test fun samsungBudsEasySetupAndBrands() {
        // 0x0075 + buds prefix + model bytes (EE 7A .. 0C) → "Fallback Buds" table entry.
        val fp = BleFingerprinter.fingerprint(adv("12FF7500 42098102141503210109 EE7A010C 063C"), null)
        assertEquals(DeviceKind.AUDIO, fp.kind)
        assertEquals("Samsung", fp.brand)
        assertTrue(fp.model.orEmpty().startsWith("Galaxy"))
        assertEquals("Apple", BleFingerprinter.fingerprint(adv("02011A 1BFF4C00 0719 010E20 000000000000000000000000000000000000000000"), null).brand)
        assertEquals("Apple", WifiFingerprinter.fingerprint("HomeNet", "3C:2E:F9:00:11:22").brand)
    }

    @Test fun appleFindMyBroadcastIsLabelledAsTracker() {
        // Seen live: manufacturer data 0x004C = 12 02 00 01 (Find My, owner nearby).
        val fp = BleFingerprinter.fingerprint(adv("07FF4C0012020001"), null)
        assertEquals(DeviceKind.TRACKER, fp.kind)
        assertTrue(fp.model.orEmpty().contains("owner nearby"))
    }
}
