package com.rfwatch.core.df

import com.rfwatch.core.geo.LocalFrame
import com.rfwatch.core.geo.bearingDeg
import com.rfwatch.core.geo.distanceM
import com.rfwatch.core.geo.haversineM
import com.rfwatch.core.model.GpsFix
import com.rfwatch.core.model.RadioObservation
import kotlin.math.exp

enum class EstimateStatus { INSUFFICIENT, LOCATED, MOBILE }

/**
 * Outcome of a drive-by localisation for one device.
 *
 * For a stationary emitter, [lat]/[lon], [ellipse], [bearingFromUserDeg] and
 * [distanceFromUserM] describe where it is. For a MOBILE emitter the position is dropped
 * (null) and [trend] carries APPROACHING/STEADY/RECEDING instead. [confidence] is always
 * present.
 */
data class EmitterEstimate(
    val status: EstimateStatus,
    val lat: Double? = null,
    val lon: Double? = null,
    val p0: Double? = null,
    val pathLossExp: Double? = null,
    val ellipse: ErrorEllipse? = null,
    val bearingFromUserDeg: Double? = null,
    val distanceFromUserM: Double? = null,
    val confidence: Double = 0.0,
    val trend: RssiTrend = RssiTrend.UNKNOWN,
    val rmsResidualDb: Double = 0.0,
    val sampleCount: Int = 0,
    val reason: String? = null,
)

/**
 * Turns a device's GPS-tagged RSSI history into an [EmitterEstimate]. Owns the gating
 * rules, ENU framing, mobility decision and the user-relative bearing/distance, delegating
 * the numerical fit to [PathLossLocalizer].
 */
object DriveByEstimator {

    const val MIN_SAMPLES = 8
    const val MIN_SPREAD_M = 100.0
    const val MIN_DISTINCT_HEADINGS = 2
    private const val HEADING_BUCKET_DEG = 45.0

    /** Semi-major axis (m) at which positional confidence falls to 1/e. */
    private const val CONF_SCALE_M = 120.0

    fun estimate(
        observations: List<RadioObservation>,
        userFix: GpsFix?,
        nowMs: Long,
        previousEstimate: EmitterEstimate? = null,
    ): EmitterEstimate {
        val withFix = observations.filter { it.fix != null }
        if (withFix.size < MIN_SAMPLES) {
            return EmitterEstimate(
                EstimateStatus.INSUFFICIENT,
                sampleCount = withFix.size,
                reason = "need $MIN_SAMPLES GPS-tagged samples, have ${withFix.size}",
            )
        }

        // Spatial spread: diagonal of the bounding box of fix positions.
        val lats = withFix.map { it.fix!!.lat }
        val lons = withFix.map { it.fix!!.lon }
        val spread = haversineM(lats.min(), lons.min(), lats.max(), lons.max())
        if (spread < MIN_SPREAD_M) {
            return EmitterEstimate(
                EstimateStatus.INSUFFICIENT,
                sampleCount = withFix.size,
                reason = "spatial spread ${spread.toInt()} m < ${MIN_SPREAD_M.toInt()} m",
            )
        }

        if (distinctHeadings(withFix) < MIN_DISTINCT_HEADINGS) {
            return EmitterEstimate(
                EstimateStatus.INSUFFICIENT,
                sampleCount = withFix.size,
                reason = "need $MIN_DISTINCT_HEADINGS distinct headings",
            )
        }

        // Frame anchored at the mean fix.
        val frame = LocalFrame(lats.average(), lons.average())
        val samples = withFix.map {
            val e = frame.toEnu(it.fix!!.lat, it.fix.lon)
            EnuSample(e.east, e.north, it.rssi.toDouble(), it.rttDistanceM, it.rttStdDevM)
        }

        val fit = PathLossLocalizer.locate(samples)

        // Drift versus the previous estimate, if any, for the mobility decision.
        val drift = previousEstimate?.let { prev ->
            if (prev.lat != null && prev.lon != null) {
                val (lat, lon) = frame.toLatLon(fit.east, fit.north)
                haversineM(prev.lat, prev.lon, lat, lon)
            } else null
        }
        val mobility = Mobility.classify(fit.rmsResidualDb, drift)

        if (mobility == MobilityClass.MOBILE) {
            return EmitterEstimate(
                status = EstimateStatus.MOBILE,
                trend = Mobility.trend(observations, nowMs),
                confidence = trendConfidence(fit.rmsResidualDb),
                rmsResidualDb = fit.rmsResidualDb,
                sampleCount = withFix.size,
                reason = "high residual/drift → emitter is moving; position suppressed",
            )
        }

        val (lat, lon) = frame.toLatLon(fit.east, fit.north)
        val confidence = positionalConfidence(fit)
        var bearing: Double? = null
        var dist: Double? = null
        if (userFix != null) {
            val u = frame.toEnu(userFix.lat, userFix.lon)
            bearing = bearingDeg(fit.east - u.east, fit.north - u.north)
            dist = distanceM(fit.east - u.east, fit.north - u.north)
        }

        return EmitterEstimate(
            status = EstimateStatus.LOCATED,
            lat = lat, lon = lon, p0 = fit.p0, pathLossExp = fit.n,
            ellipse = fit.ellipse,
            bearingFromUserDeg = bearing, distanceFromUserM = dist,
            confidence = confidence,
            trend = RssiTrend.UNKNOWN,
            rmsResidualDb = fit.rmsResidualDb,
            sampleCount = withFix.size,
        )
    }

    /** Count of distinct 45°-bucketed travel headings across the samples. */
    private fun distinctHeadings(obs: List<RadioObservation>): Int {
        val buckets = HashSet<Int>()
        var prev: GpsFix? = null
        for (o in obs) {
            val f = o.fix ?: continue
            val h = when {
                f.hasBearing -> f.bearingDeg
                prev != null -> courseBetween(prev!!, f)
                else -> null
            }
            if (h != null) buckets.add((((h % 360.0) + 360.0) % 360.0 / HEADING_BUCKET_DEG).toInt())
            prev = f
        }
        return buckets.size
    }

    private fun courseBetween(a: GpsFix, b: GpsFix): Double? {
        val frame = LocalFrame(a.lat, a.lon)
        val e = frame.toEnu(b.lat, b.lon)
        if (distanceM(e.east, e.north) < 2.0) return null
        return bearingDeg(e.east, e.north)
    }

    private fun positionalConfidence(fit: LocalizationFit): Double {
        val base = exp(-fit.ellipse.semiMajorM / CONF_SCALE_M)
        val conv = if (fit.converged && !fit.usedFallback) 1.0 else 0.5
        return (base * conv).coerceIn(0.0, 1.0)
    }

    private fun trendConfidence(rms: Double): Double =
        (1.0 - (rms - Mobility.residualMobileDb) / 20.0).coerceIn(0.2, 0.8)
}
