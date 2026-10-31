package com.rfwatch.app.repo

import com.rfwatch.app.data.ObservationEntity
import org.junit.Assert.assertEquals
import org.junit.Test

class MergeSightingsTest {
    private fun obs(lat: Double?, rssi: Int, t: Long) =
        ObservationEntity(deviceId = "d", band = "BLE", rssi = rssi, timestampMs = t, lat = lat, lon = lat?.let { 0.0 }, gpsAccuracyM = 10.0)

    @Test fun repeatedFixesBecomeOnePointWithStrongestRssiAndCount() {
        val rows = listOf(obs(1.0, -80, 1), obs(1.0, -60, 2), obs(1.0, -70, 3), obs(1.0001, -90, 4), obs(null, -40, 5))
        val merged = mergeSightings(rows).sortedBy { it.lat }
        assertEquals(2, merged.size)
        assertEquals(-60, merged[0].rssi)
        assertEquals(3, merged[0].count)
        assertEquals(3L, merged[0].timestampMs)
        assertEquals(1, merged[1].count)
        assertEquals(4, LocateData(sightings = merged, centroid = null, driveBy = null).hearings)
    }
}
