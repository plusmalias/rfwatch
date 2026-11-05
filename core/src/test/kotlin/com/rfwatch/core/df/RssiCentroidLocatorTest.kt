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

    @Test fun indoorGpsJitterIsNotMistakenForMovement() {
        // Sitting still indoors: fixes wander ±6 m with 8 m reported accuracy.
        val jitter = listOf(0.0 to 0.0, 6.0 to -4.0, -5.0 to 5.0, 3.0 to 6.0, -6.0 to -3.0, 5.0 to 2.0, -2.0 to -6.0)
        val samples = jitter.mapIndexed { i, (e, n) ->
            val (lat, lon) = frame.toLatLon(e, n)
            RadioObservation("d", Band.BLE, -65 - i % 4, 0, fix = GpsFix(lat, lon, 8.0, 0))
        }
        assertFalse(RssiCentroidLocator.estimate(samples)!!.directional)
    }

    @Test fun oneBadFixFarAwayIsNotMistakenForMovement() {
        // Seen on the phone 2026-11-01: 7 sightings at home plus one ±80 m network-style fix 56 m away
        // produced "56 m spread" in 34 s and a fake dot. Poor fixes must not create spread.
        val home = List(7) { obs(0.3 * (it % 3), 0.2 * (it % 2), -62 - it % 4) }
        val (lat, lon) = frame.toLatLon(56.0, 0.0)
        val glitch = RadioObservation("d", Band.BLE, -70, 0, fix = GpsFix(lat, lon, 80.0, 0))
        assertFalse(RssiCentroidLocator.estimate(home + glitch)!!.directional)
    }

    @Test fun onlyPoorFixesGiveANonDirectionalEstimate() {
        val poor = (0..9).map { i ->
            val (lat, lon) = frame.toLatLon(i * 10.0, 0.0)
            RadioObservation("d", Band.BLE, -60 - i, 0, fix = GpsFix(lat, lon, 60.0, 0))
        }
        assertFalse(RssiCentroidLocator.estimate(poor)!!.directional)
    }

    private fun loop(target: Pair<Double, Double>, noise: (Int) -> Int = { 0 }) =
        (0..30).flatMap { i -> listOf(i.toDouble() to 0.0, 30.0 to i.toDouble(), (30 - i).toDouble() to 30.0, 0.0 to (30 - i).toDouble()) }
            .mapIndexed { i, (e, n) ->
                val d = hypot(e - target.first, n - target.second).coerceAtLeast(1.0)
                obs(e, n, (-59 - 24 * log10(d)).toInt() + noise(i))
            }

    @Test fun emitterOutsideTheWalkedAreaIsNotPlacedInsideIt() {
        // Seen on the phone 2026-11-05: walking ~40 m round the house "located" neighbours' routers a
        // few metres from the user (the centroid of the user's own positions). The signal only rose
        // toward one edge, so the device is beyond it: direction unknown, not a position.
        assertFalse(RssiCentroidLocator.estimate(loop(70.0 to 15.0))!!.directional)
    }

    @Test fun flatSignalWhileWalkingIsNotAPosition() {
        // A strong distant AP: ±3 dB of noise everywhere on the loop, no peak to walk around.
        val flat = loop(15.0 to 15.0).mapIndexed { i, o -> o.copy(rssi = -78 + (i * 7 % 7) - 3) }
        assertFalse(RssiCentroidLocator.estimate(flat)!!.directional)
    }
}
