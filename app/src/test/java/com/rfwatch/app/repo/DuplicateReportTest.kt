package com.rfwatch.app.repo

import com.rfwatch.app.data.DeviceEntity
import com.rfwatch.app.data.ObservationEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class DuplicateReportTest {
    private fun ble(mac: String, first: Long, last: Long, model: String? = "AirPods Pro", lat: Double? = 1.0) = DeviceEntity(
        id = mac, band = "BLE", displayName = null, lastMac = mac, firstSeenMs = first, lastSeenMs = last, lastRssi = -60,
        brand = "Apple", model = model, kind = "AUDIO", observedLat = lat, observedLon = lat,
    )

    @Test fun findsRotationsStacksMultiBssidAndLocateRepeats() {
        val devices = listOf(
            ble("7A:00:00:00:00:01", 0, 900_000),
            ble("7A:00:00:00:00:02", 900_000, 1_800_000),
            ble("7A:00:00:00:00:03", 1_800_000, 2_700_000),
            ble("00:11:22:33:44:55", 0, 10, model = null, lat = 2.0), // public address: not rotation
            DeviceEntity(id = "AA:BB:CC:DD:EE:01", band = "WIFI", displayName = "HomeNet", lastMac = "AA:BB:CC:DD:EE:01", firstSeenMs = 0, lastSeenMs = 1, lastRssi = -50),
            DeviceEntity(id = "AA:BB:CC:DD:EE:02", band = "WIFI", displayName = "HomeNet", lastMac = "AA:BB:CC:DD:EE:02", firstSeenMs = 0, lastSeenMs = 1, lastRssi = -50),
        )
        val obs = List(10) { ObservationEntity(deviceId = "x", band = "BLE", rssi = -60, timestampMs = it.toLong(), lat = if (it < 8) 5.0 else 5.001, lon = 5.0) }
        val r = DuplicateReport.analyze(devices, mapOf(devices[0] to obs))
        assertEquals(4, r.mappedDevices)
        assertEquals(2, r.distinctMarkerPoints)
        assertEquals(3, r.largestStack)
        assertEquals(listOf("Apple/AirPods Pro/AUDIO/-" to 3), r.rotatingGroups)
        assertEquals(listOf("HomeNet" to 2), r.ssidGroups)
        assertEquals(1, r.sameApPrefixGroups)
        assertEquals(Triple("AUDIO", 10, 2), r.locateSamples.single())
    }
}
