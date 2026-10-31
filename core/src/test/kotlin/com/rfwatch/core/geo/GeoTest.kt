package com.rfwatch.core.geo

import kotlin.math.abs
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoTest {

    @Test
    fun enuRoundTripIsSubMetre() {
        val frame = LocalFrame(-37.8136, 144.9631) // Melbourne
        val points = listOf(
            -37.8136 to 144.9631,
            -37.8100 to 144.9700,
            -37.8200 to 144.9550,
            -37.8050 to 144.9800,
        )
        for ((lat, lon) in points) {
            val e = frame.toEnu(lat, lon)
            val (lat2, lon2) = frame.toLatLon(e.east, e.north)
            val err = haversineM(lat, lon, lat2, lon2)
            assertTrue("round-trip error $err m should be < 0.5 m", err < 0.5)
        }
    }

    @Test
    fun bearingCardinals() {
        assertEquals(0.0, bearingDeg(0.0, 10.0), 1e-6)   // north
        assertEquals(90.0, bearingDeg(10.0, 0.0), 1e-6)  // east
        assertEquals(180.0, bearingDeg(0.0, -10.0), 1e-6) // south
        assertEquals(270.0, bearingDeg(-10.0, 0.0), 1e-6) // west
    }

    @Test
    fun haversineKnownDistance() {
        // ~1 degree of latitude ≈ 111.19 km on the WGS84 mean sphere.
        val d = haversineM(0.0, 0.0, 1.0, 0.0)
        assertTrue("got $d", abs(d - 111_195.0) < 200.0)
    }

    @Test
    fun angularDiffWraps() {
        assertEquals(10.0, angularDiffDeg(5.0, 355.0), 1e-9)
        assertEquals(180.0, angularDiffDeg(0.0, 180.0), 1e-9)
        assertEquals(0.0, angularDiffDeg(123.0, 123.0), 1e-9)
    }
}
