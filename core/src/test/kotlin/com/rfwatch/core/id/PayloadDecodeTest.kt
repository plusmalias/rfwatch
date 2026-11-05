package com.rfwatch.core.id

import com.rfwatch.core.cs.AdvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Fixtures follow the layout of real adverts seen in the field; every identifier, key, name and
 * address inside them is altered (structure bytes kept so the decoders see the same shapes).
 */
class PayloadDecodeTest {

    private fun adv(hex: String) = AdvParser.fromRawAdvBytes(AdvParser.hex(hex), "AA:BB:CC:DD:EE:FF", -60, 0)
    private fun fp(hex: String, name: String? = null) = BleFingerprinter.fingerprint(adv(hex), name)

    @Test fun duplicateManufacturerRecordsAreMergedNotOverwritten() {
        // Nearby Info record followed by a second Apple record (overflow area) in the scan response.
        val a = adv("02011A020A0C0CFF4C0010073E1F0A1BCC2D4514FF4C000100000000000000000000008000000000")
        assertEquals(2, a.manufacturerRecords.size)
        assertEquals(0x1A, a.flags)
        val f = BleFingerprinter.fingerprint(a, null)
        assertEquals(DeviceKind.PHONE, f.kind)
        assertEquals("Apple", f.brand)
    }

    @Test fun appleUnknownContinuityTypeUsesDualModeFlags() {
        val phone = fp("02011A0DFF4C00160800E1A0119ACB25DF")
        assertEquals(DeviceKind.PHONE, phone.kind)
        assertEquals("iPhone / iPad / Mac", phone.model)
        assertTrue(phone.evidence.contains("0x16"))
        // Same message without a flags structure: still Apple, kind not guessed.
        val bare = fp("0DFF4C00160800E1A0119ACB25DF")
        assertEquals(DeviceKind.UNKNOWN, bare.kind)
        assertEquals("Apple", bare.brand)
        assertEquals("Apple device", bare.model)
    }

    @Test fun airPrintAndAirPlayRevealLocalAddress() {
        val printer = fp("0201061BFF4C0003161100000277C0A8010A000000000000000000000000C9")
        assertEquals("AirPrint printer", printer.model)
        assertTrue(printer.evidence, printer.evidence.contains("192.168.1.10:631"))
        val tv = fp("02011A17FF4C00090813BFC0A800141B58160800A1B2C3D4E5F607")
        assertEquals(DeviceKind.TV_MEDIA, tv.kind)
        assertTrue(tv.evidence, tv.evidence.contains("192.168.0.20"))
        // 0.0.0.0 is "no address", not shown.
        assertTrue(!fp("02011A0BFF4C0009060100000000000000").evidence.contains("0.0.0.0"))
    }

    @Test fun proximityPairingBatteryLevels() {
        // AirPods Pro, pods 90 % / 80 %, case 50 % (charge nibble 0, case nibble 5).
        val f = fp("1EFF4C000719010E20559805" + "00".repeat(19))
        assertEquals("AirPods Pro", f.model)
        assertTrue(f.evidence, f.evidence.contains("90%") && f.evidence.contains("80%") && f.evidence.contains("case 50%"))
    }

    @Test fun samsungNameFrameIsSplitAcrossTwoRecords() {
        // 01 12 frame: header bytes, then the owner's device name continued in a second record.
        val f = fp("02011A1BFF750001126FB0E2B3000000000254A1B2C301014A6F27732047611EFF750001126C6178792041353273" + "00".repeat(16))
        assertEquals("Jo's Galaxy A52s", f.name)
        assertEquals(DeviceKind.PHONE, f.kind)
        assertEquals("Samsung", f.brand)
    }

    @Test fun samsungOtherFramesNamedHonestly() {
        val f = fp("1BFF750042040180A1B2C3D4E5F6F1F6EDFAE6EF70013D1035EFD5B4")
        assertEquals("Samsung", f.brand)
        assertEquals("Samsung Galaxy device", f.model)
    }

    @Test fun iBeaconFromAnyCompanyAndByteSwappedClones() {
        val nordic = fp("0201061AFF590002156163 6D652E426561636F6E494430303045EA0139C3".replace(" ", ""))
        assertEquals(DeviceKind.BEACON, nordic.kind)
        assertTrue(nordic.evidence, nordic.evidence.contains("acme.BeaconID"))
        val clone = fp("0201041AFF004C02151F2E3D4C5B6A7988010203040506070800010000C9")
        assertEquals(DeviceKind.BEACON, clone.kind)
        assertTrue(clone.model!!.contains("clone"))
    }

    @Test fun googleServiceFramesAndHp() {
        val g = fp("1E16F3FE9C1723A7B6C54A1132B4D11977E54EF0550BC3AF37866301C3365F")
        assertEquals("Google", g.brand)
        assertEquals("Google / Android device", g.model)
        val hp = fp("0201061916F7FD015C07A1B2C3D44CBFB2D13CA2ADF340BE0000000003030378FE06FF650001C901")
        assertEquals("HP", hp.brand)
        assertNotNull(hp.model)
    }

    @Test fun payloadTextNeedsRealWords() {
        assertEquals(listOf("acme.BeaconID"), PayloadText.find(AdvParser.hex("02156163 6D652E426561636F6E4944EAAC".replace(" ", ""))))
        // Random bytes with a few printable characters are not text.
        assertTrue(PayloadText.find(AdvParser.hex("9C1723A7B6C54A1132B4")).isEmpty())
    }

    @Test fun flagsOnlyAdvertIsLabelled() {
        val f = fp("020106")
        assertEquals(DeviceKind.UNKNOWN, f.kind)
        assertNull(f.brand)
        assertEquals("BLE device (no payload)", f.model)
    }

    @Test fun breakdownLabelsEveryStructure() {
        val lines = AdvDecoder.describe(AdvParser.hex("02011A020A0C0CFF4C0010073E1F0A1BCC2D450909426F622773205456"))
        assertTrue(lines.joinToString("\n"), lines.any { it.text.startsWith("Flags") && it.text.contains("BR/EDR") })
        assertTrue(lines.any { it.text.contains("TX power 12 dBm") })
        assertTrue(lines.any { it.text.contains("Apple") && it.text.contains("Nearby Info") })
        assertTrue(lines.any { it.text.contains("\"Bob's TV\"") })
        assertEquals("02011A", lines.first().hex)
    }
}
