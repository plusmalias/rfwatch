package com.rfwatch.core.df

import com.rfwatch.core.geo.LocalFrame
import com.rfwatch.core.geo.haversineM
import com.rfwatch.core.model.Band
import com.rfwatch.core.model.GpsFix
import com.rfwatch.core.model.RadioObservation
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot
import kotlin.math.log10

class RssiCentroidLocatorTest {

    private val frame = LocalFrame(-18.70, 146.29)

    private fun obs(east: Double, north: Double, rssi: Int): RadioObservation {
        val (lat, lon) = frame.toLatLon(east, north)
        return RadioObservation("d", Band.BLE, rssi, 0, fix = GpsFix(lat, lon, 3.0, 0))
    }

    @Test fun walkingAroundAnEmitterLocatesItWithinAFewMetres() {
        // Emitter at (12, 5); user walks a 30 m square loop; RSSI follows log-distance.
        val target = 12.0 to 5.0
        val walk = (0..30).flatMap { i -> listOf(i.toDouble() to 0.0, 30.0 to i.toDouble(), (30 - i).toDouble() to 30.0, 0.0 to (30 - i).toDouble()) }
        val samples = walk.map { (e, n) ->
            val d = hypot(e - target.first, n - target.second).coerceAtLeast(1.0)
            obs(e, n, (-59 - 24 * log10(d)).toInt())
        }
        val est = RssiCentroidLocator.estimate(samples)!!
        val (tLat, tLon) = frame.toLatLon(target.first, target.second)
        assertTrue(est.directional)
        assertTrue("error ${haversineM(est.lat, est.lon, tLat, tLon)} m", haversineM(est.lat, est.lon, tLat, tLon) < 8.0)
    }

    @Test fun standingStillIsNotDirectional() {
        val est = RssiCentroidLocator.estimate(List(10) { obs(0.5 * (it % 2), 0.0, -70 - it % 3) })!!
        assertFalse(est.directional)
        assertTrue(est.radiusM >= RssiCentroidLocator.rssiRangeM(-70))
    }

    @Test fun tooFewSamplesGivesNothing() {
        assertNull(RssiCentroidLocator.estimate(listOf(obs(0.0, 0.0, -60), obs(5.0, 0.0, -65))))
    }
}
