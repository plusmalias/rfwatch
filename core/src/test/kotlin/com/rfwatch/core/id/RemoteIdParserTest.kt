package com.rfwatch.core.id

import com.rfwatch.core.cs.AdvParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RemoteIdParserTest {

    private fun le32(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte(), (v shr 16).toByte(), (v shr 24).toByte())
    private fun le16(v: Int) = byteArrayOf(v.toByte(), (v shr 8).toByte())

    private fun basicId(serial: String) = ByteArray(25).also { m ->
        m[0] = 0x02 // type 0, protocol v2
        m[1] = ((1 shl 4) or 2).toByte() // serial-number ID, multirotor
        serial.toByteArray().copyInto(m, 2)
    }

    private fun location(lat: Double, lon: Double, altM: Double) = ByteArray(25).also { m ->
        m[0] = 0x12 // type 1
        m[1] = 0x20 // airborne, speed mult 0, EW 0
        m[2] = 90 // heading 90°
        m[3] = 20 // 5 m/s
        le32((lat * 1e7).toInt()).copyInto(m, 5)
        le32((lon * 1e7).toInt()).copyInto(m, 9)
        le16(((altM + 1000) / 0.5).toInt()).copyInto(m, 15)
    }

    private fun system(lat: Double, lon: Double) = ByteArray(25).also { m ->
        m[0] = 0x42 // type 4
        le32((lat * 1e7).toInt()).copyInto(m, 2)
        le32((lon * 1e7).toInt()).copyInto(m, 6)
    }

    @Test fun bleBasicIdThroughAdvParser() {
        val svc = byteArrayOf(0x0D, 0x01) + basicId("1581F5FKD229400BA9DY")
        val adv = byteArrayOf((svc.size + 3).toByte(), 0x16, 0xFA.toByte(), 0xFF.toByte()) + svc
        val parsed = AdvParser.fromRawAdvBytes(adv, "AA:BB:CC:DD:EE:FF", -70, 0)
        val info = RemoteIdParser.parseBleServiceData(parsed.serviceData16[RemoteIdParser.BLE_SERVICE_UUID]!!)!!
        assertEquals("1581F5FKD229400BA9DY", info.serial)
        assertEquals("Multirotor / helicopter", info.uaType)
        val fp = BleFingerprinter.fingerprint(parsed, null)
        assertEquals(DeviceKind.DRONE, fp.kind)
    }

    @Test fun messagePackGivesDroneAndPilotPositions() {
        val pack = byteArrayOf(0xF2.toByte(), 25, 3) + basicId("SN123") + location(-18.7, 146.3, 85.0) + system(-18.701, 146.301)
        val body = RemoteIdParser.WIFI_OUI + byteArrayOf(0x0D, 0x07) + pack
        val info = RemoteIdParser.parseWifiVendorElement(body)!!
        assertEquals("SN123", info.serial)
        assertEquals(-18.7, info.lat!!, 1e-6)
        assertEquals(146.3, info.lon!!, 1e-6)
        assertEquals(85.0, info.altitudeM!!, 0.6)
        assertEquals(5.0, info.speedMps!!, 0.01)
        assertEquals(90.0, info.headingDeg!!, 0.01)
        assertEquals(-18.701, info.operatorLat!!, 1e-6)
        assertTrue(info.operatorLon!! > 146.0)
    }

    @Test fun rejectsNonRemoteIdData() {
        assertNull(RemoteIdParser.parseBleServiceData(byteArrayOf(0x01, 0x02, 0x03)))
        assertNull(RemoteIdParser.parseWifiVendorElement(byteArrayOf(0x00, 0x50, 0xF2.toByte(), 0x04, 0x10)))
    }
}
