package com.rfwatch.core.df

import com.rfwatch.core.geo.LocalFrame
import com.rfwatch.core.model.RadioObservation
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * A device position from RSSI-weighted GPS sightings.
 *
 * @param directional true only when the sightings were spread over at least
 *        [RssiCentroidLocator.MIN_SPREAD_M] and well beyond GPS jitter; otherwise every sighting came from (almost) one spot
 *        and the estimate says nothing about direction — only "near where you were".
 * @param radiusM 1-sigma-ish uncertainty radius in metres (sample scatter + GPS accuracy + a
 *        floor from the RSSI distance proxy of the strongest reading).
 */
data class CentroidEstimate(
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
    val spreadM: Double,
    val samples: Int,
    val strongestRssi: Int,
    val directional: Boolean,
)

/**
 * Weighted-centroid localisation: the emitter is most likely near where its signal was
 * strongest, so each GPS-tagged sighting is weighted by its linear received power relative to
 * the strongest one (10^((rssi − max)/10)), using only sightings within [WINDOW_DB] of the peak.
 * Works with a few metres of walking (unlike the path-loss fit in [DriveByEstimator], which needs
 * ≥100 m of spread) and degrades honestly: with no spread, [CentroidEstimate.directional] is false.
 */
object RssiCentroidLocator {

    const val MIN_SAMPLES = 3
    const val MIN_SPREAD_M = 25.0
    /** Movement must also exceed this multiple of the typical GPS error, or it is just jitter. */
    const val SPREAD_OVER_ACCURACY = 3.0
    const val WINDOW_DB = 12.0
    /** Fixes reporting worse accuracy than this are ignored for position and spread. */
    const val MAX_FIX_ACCURACY_M = 20.0

    fun estimate(observations: List<RadioObservation>): CentroidEstimate? {
        val all = observations.filter { it.fix != null }
        if (all.size < MIN_SAMPLES) return null
        // Poor fixes (network location, GPS warm-up) land tens of metres off and would fake "spread"
        // on their own, so direction is judged from good fixes only. Without enough of them the
        // estimate is still returned, but never directional.
        val good = all.filter { it.fix!!.accuracyM <= MAX_FIX_ACCURACY_M }
        val enoughGood = good.size >= MIN_SAMPLES
        val geo = if (enoughGood) good else all
        val maxRssi = geo.maxOf { it.rssi }
        val near = geo.filter { it.rssi >= maxRssi - WINDOW_DB }
        val frame = LocalFrame(near.map { it.fix!!.lat }.average(), near.map { it.fix!!.lon }.average())
        val pts = near.map { o ->
            val e = frame.toEnu(o.fix!!.lat, o.fix.lon)
            Triple(e.east, e.north, 10.0.pow((o.rssi - maxRssi) / 10.0)) to o.fix.accuracyM
        }
        val wSum = pts.sumOf { it.first.third }
        val east = pts.sumOf { it.first.first * it.first.third } / wSum
        val north = pts.sumOf { it.first.second * it.first.third } / wSum
        val scatter = sqrt(pts.sumOf { (p, _) -> p.third * ((p.first - east).pow(2) + (p.second - north).pow(2)) } / wSum)
        val gpsAcc = pts.sumOf { (p, acc) -> p.third * acc } / wSum

        val allEnu = geo.map { frame.toEnu(it.fix!!.lat, it.fix.lon) }
        val spread = hypot(allEnu.maxOf { it.east } - allEnu.minOf { it.east }, allEnu.maxOf { it.north } - allEnu.minOf { it.north })
        val typicalAccuracy = geo.map { it.fix!!.accuracyM }.sorted()[geo.size / 2]
        val directional = enoughGood && spread >= MIN_SPREAD_M && spread >= SPREAD_OVER_ACCURACY * typicalAccuracy
        // Without spread the device could be anywhere around the spot at roughly the RSSI range.
        val rangeFloor = rssiRangeM(maxRssi)
        val radius = if (directional) max(scatter + gpsAcc, rangeFloor * 0.5) else max(rangeFloor, gpsAcc)

        val (lat, lon) = frame.toLatLon(east, north)
        return CentroidEstimate(lat, lon, radius, spread, near.size, maxRssi, directional)
    }

    /** Uncalibrated log-distance proxy (n = 2.4, −59 dBm at 1 m); relative use only. */
    fun rssiRangeM(rssi: Int): Double = 10.0.pow((-59.0 - rssi.coerceIn(-110, -20)) / 24.0).coerceIn(0.5, 100.0)
}
