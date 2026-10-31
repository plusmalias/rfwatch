package com.rfwatch.core.cs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackerParserTest {

    private fun load(name: String): BleAdvertisement {
        val hex = javaClass.getResource("/trackers/$name")!!.readText().trim()
        return AdvParser.fromRawAdvBytes(AdvParser.hex(hex), address = "AA:BB:CC:DD:EE:FF", rssi = -60, timestampMs = 0)
    }

    @Test
    fun appleAirtagSeparated() {
        val m = TrackerParser.parse(load("apple_airtag_separated.hex"))
        assertNotNull(m)
        assertEquals(TrackerType.APPLE_FIND_MY, m!!.type)
        assertEquals(TrackerState.SEPARATED, m.state)
        assertTrue(m.confidence > 0.9)
    }

    @Test
    fun samsungSmartTagRegistered() {
        val m = TrackerParser.parse(load("samsung_smarttag_registered.hex"))
        assertNotNull(m)
        assertEquals(TrackerType.SAMSUNG_SMARTTAG, m!!.type)
        assertEquals(true, m.registered)
    }

    @Test
    fun tileByServiceUuid() {
        val m = TrackerParser.parse(load("tile.hex"))
        assertNotNull(m)
        assertEquals(TrackerType.TILE, m!!.type)
    }

    @Test
    fun googleFmdnUnwantedTrackingMode() {
        val m = TrackerParser.parse(load("google_fmdn_utp.hex"))
        assertNotNull(m)
        assertEquals(TrackerType.GOOGLE_FIND_MY, m!!.type)
        assertEquals(TrackerState.SEPARATED, m.state) // frame 0x41 = UTP mode
    }

    @Test
    fun ordinaryAppleManufacturerPayloadIsNotFindMyTracker() {
        val adv = BleAdvertisement(
            manufacturerData = mapOf(TrackerParser.APPLE_COMPANY_ID to byteArrayOf(0x07, 0x01, 0x02)),
            serviceData16 = emptyMap(), serviceUuids16 = emptySet(),
            address = "AA:BB:CC:DD:EE:FF", rssi = -55, timestampMs = 0,
        )
        assertNull(TrackerParser.parse(adv))
    }

    @Test
    fun legacyEddystoneFrameIsNotGoogleFindMyTracker() {
        val adv = BleAdvertisement(
            manufacturerData = emptyMap(),
            serviceData16 = mapOf(0xFEAA to byteArrayOf(0x10, 0x00)),
            serviceUuids16 = setOf(0xFEAA),
            address = "AA:BB:CC:DD:EE:FF", rssi = -55, timestampMs = 0,
        )
        assertNull(TrackerParser.parse(adv))
    }

    @Test
    fun ordinaryDeviceIsNotATracker() {
        assertNull(TrackerParser.parse(load("not_a_tracker.hex")))
    }

    @Test
    fun linksAcrossAddressRotation() {
        val match = TrackerMatch(TrackerType.APPLE_FIND_MY, TrackerState.SEPARATED, BatteryHint.LOW, null, 0.95, "x")
        // Same tracker, three different MACs, steady RSSI, 2 min apart → one identity.
        val obs = listOf(
            TrackerObservation("MAC:1", -55, 0, match),
            TrackerObservation("MAC:2", -57, 120_000, match),
            TrackerObservation("MAC:3", -54, 240_000, match),
        )
        val ids = TrackerIdentityLinker().link(obs)
        assertEquals(1, ids.size)
        assertEquals(3, ids[0].addresses.size)
        assertEquals(3, ids[0].observations)
    }

    @Test
    fun separatesDistinctRssiClusters() {
        val match = TrackerMatch(TrackerType.TILE, TrackerState.UNKNOWN, BatteryHint.UNKNOWN, null, 0.9, "x")
        // One very close (-40) and one far (-90): RSSI gap > threshold → two identities.
        val obs = listOf(
            TrackerObservation("MAC:A", -40, 0, match),
            TrackerObservation("MAC:B", -90, 5_000, match),
            TrackerObservation("MAC:A2", -42, 10_000, match),
        )
        val ids = TrackerIdentityLinker().link(obs)
        assertEquals(2, ids.size)
    }
}
