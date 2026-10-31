package com.rfwatch.core.df

import com.rfwatch.core.model.GpsFix
import com.rfwatch.core.model.HeadingSource
import com.rfwatch.core.model.SensorAccuracy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadingFusionTest {

    private fun fix(bearing: Double? = null, speed: Double? = null) = GpsFix(
        lat = -37.81, lon = 144.96, accuracyM = 5.0, timestampMs = 0,
        bearingDeg = bearing, speedMps = speed,
    )

    @Test
    fun movingFastUsesGpsBearing() {
        val h = HeadingFusion.fuse(
            magneticHeadingDeg = 10.0, declinationDeg = 11.0,
            accuracy = SensorAccuracy.HIGH, fieldMagnitudeUT = 48.0,
            gps = fix(bearing = 200.0, speed = 15.0),
        )
        assertEquals(HeadingSource.GPS_BEARING, h.source)
        assertEquals(200.0, h.deg!!, 1e-6)
    }

    @Test
    fun stationaryCleanCompassUsesTrueNorth() {
        val h = HeadingFusion.fuse(
            magneticHeadingDeg = 10.0, declinationDeg = 11.5,
            accuracy = SensorAccuracy.HIGH, fieldMagnitudeUT = 48.0,
            gps = fix(speed = 0.5),
        )
        assertEquals(HeadingSource.COMPASS, h.source)
        assertEquals(21.5, h.deg!!, 1e-6) // magnetic + declination
    }

    @Test
    fun disturbedFieldFallsBackToGps() {
        val h = HeadingFusion.fuse(
            magneticHeadingDeg = 10.0, declinationDeg = 11.0,
            accuracy = SensorAccuracy.HIGH, fieldMagnitudeUT = 120.0, // ferrous disturbance
            gps = fix(bearing = 95.0, speed = 1.0),
        )
        assertEquals(HeadingSource.GPS_BEARING, h.source)
        assertEquals(95.0, h.deg!!, 1e-6)
    }

    @Test
    fun lowAccuracyWithNoGpsBearingReportsNone() {
        val h = HeadingFusion.fuse(
            magneticHeadingDeg = 10.0, declinationDeg = 11.0,
            accuracy = SensorAccuracy.LOW, fieldMagnitudeUT = 48.0,
            gps = fix(speed = 0.0),
        )
        assertEquals(HeadingSource.NONE, h.source)
        assertNull(h.deg)
    }

    @Test
    fun compassWrapsPastNorth() {
        val h = HeadingFusion.fuse(
            magneticHeadingDeg = 355.0, declinationDeg = 10.0,
            accuracy = SensorAccuracy.HIGH, fieldMagnitudeUT = 50.0,
            gps = null,
        )
        assertEquals(5.0, h.deg!!, 1e-6) // 355 + 10 = 365 → 5
    }
}
