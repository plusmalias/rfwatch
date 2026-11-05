package com.rfwatch.core.id

import com.rfwatch.core.cs.AdvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WifiIeTest {

    private fun tlv(type: Int, value: String): String {
        val b = value.toByteArray()
        return "%04X%04X".format(type, b.size) + b.joinToString("") { "%02X".format(it) }
    }

    /** WPS vendor element body: OUI 00:50:F2, type 0x04, then big-endian TLV attributes. */
    private fun wps(vararg tlvs: String) = AdvParser.hex("0050F204" + tlvs.joinToString(""))

    @Test fun wpsAttributesNameTheDevice() {
        val body = wps(
            "104A000110", // version
            tlv(0x1021, "ACME Corp"), tlv(0x1023, "CamPro"), tlv(0x1024, "CP-200"), tlv(0x1011, "Porch Cam"),
            "1054000800040050F2040001", // primary device type: category 4 (camera)
        )
        val info = WifiIes.parse(listOf(221 to body))
        val w = info.wps!!
        assertEquals("ACME Corp", w.manufacturer)
        assertEquals("CamPro", w.modelName)
        assertEquals("CP-200", w.modelNumber)
        assertEquals("Porch Cam", w.deviceName)
        assertEquals("Camera", w.category)
        val fp = WifiFingerprinter.fingerprint("Porch-5G", "3C:2E:F9:00:11:22", info)
        assertEquals(DeviceKind.CAMERA_IOT, fp.kind)
        assertEquals("ACME CamPro CP-200", fp.model)
        assertEquals("ACME", fp.brand)
        assertTrue(fp.evidence.contains("WPS"))
    }

    @Test fun routerWpsKeepsNetworkKindButGainsModel() {
        val info = WifiIes.parse(listOf(221 to wps(tlv(0x1021, "NETGEAR, Inc."), tlv(0x1023, "R7000"), "1054000800060050F2040001")))
        val fp = WifiFingerprinter.fingerprint("HomeNet", "06:11:22:33:44:55", info)
        assertEquals(DeviceKind.NETWORK, fp.kind)
        assertEquals("NETGEAR R7000", fp.model)
    }

    @Test fun vendorElementsGiveChipsetAndApple() {
        val info = WifiIes.parse(listOf(221 to AdvParser.hex("00101802000000"), 221 to AdvParser.hex("0017F2060100"), 48 to AdvParser.hex("0100")))
        assertNull(info.wps)
        assertTrue(info.vendors.contains("Broadcom"))
        assertTrue(info.vendors.contains("Apple"))
        val fp = WifiFingerprinter.fingerprint("Jo's iPhone", "06:11:22:33:44:55", info)
        assertEquals(DeviceKind.HOTSPOT, fp.kind)
        assertEquals("Apple", fp.brand)
    }

    @Test fun malformedWpsIsIgnored() {
        assertNull(WifiIes.parse(listOf(221 to AdvParser.hex("0050F2041021FFFF41"))).wps?.manufacturer)
    }
}
