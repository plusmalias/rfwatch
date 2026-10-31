package com.rfwatch.core.df

import com.rfwatch.core.math.LinAlg
import com.rfwatch.core.math.median
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** One 10° sector of a sweep: its centre bearing and the median RSSI seen there. */
data class SweepBin(val centerDeg: Double, val medianRssi: Double, val count: Int)

/**
 * Result of a stationary heading-sweep fit.
 *
 * @param bearingDeg direction of the fitted lobe's peak (toward the emitter), or null
 *        when the fit is too weak to claim a direction.
 * @param amplitudeDb peak-to-mean depth `b` of the lobe, in dB.
 * @param offsetDb the fitted mean level `a`.
 * @param r2 coefficient of determination of the cosine fit against the binned medians.
 * @param confidence 0..1, folding together R² and lobe depth. Always present; the UI
 *        must never show a bearing without it.
 */
data class SweepResult(
    val bearingDeg: Double?,
    val amplitudeDb: Double,
    val offsetDb: Double,
    val r2: Double,
    val confidence: Double,
    val bins: List<SweepBin>,
)

/**
 * Fits the body-shadowing model `rssi(θ) = a + b·cos(θ − φ)` to RSSI-versus-heading
 * samples gathered while the user turns slowly through 360° with the phone held against
 * the chest. The body attenuates signals arriving from behind, so received power peaks
 * when the user faces the emitter — hence bearing = φ.
 *
 * Method: readings are binned into [SECTOR_DEG]-wide sectors and reduced to a per-sector
 * median (robust to multipath spikes). The model is linear in the unknowns once expanded
 * as `a + c·cosθ + d·sinθ`, so a 3×3 normal-equation solve recovers (a, c, d); then
 * `b = hypot(c, d)` and `φ = atan2(d, c)`.
 */
object SweepFit {

    const val SECTOR_DEG = 10.0
    private const val DEG2RAD = Math.PI / 180.0
    private const val RAD2DEG = 180.0 / Math.PI

    /** Lobe depth at which the depth term of confidence reaches 0.5 (dB). */
    private const val DEPTH_HALF_DB = 3.0

    /** Below this confidence the bearing is suppressed ("no clear direction"). */
    const val MIN_CONFIDENCE = 0.30

    /**
     * @param headingsDeg user heading (deg, true north) at each sample.
     * @param rssi corresponding RSSI (dBm).
     */
    fun fit(headingsDeg: DoubleArray, rssi: DoubleArray): SweepResult {
        require(headingsDeg.size == rssi.size) { "headings and rssi length mismatch" }

        // Bin into sectors and take the median per populated sector.
        val buckets = HashMap<Int, MutableList<Double>>()
        for (i in headingsDeg.indices) {
            val norm = ((headingsDeg[i] % 360.0) + 360.0) % 360.0
            val idx = (norm / SECTOR_DEG).toInt().coerceIn(0, (360.0 / SECTOR_DEG).toInt() - 1)
            buckets.getOrPut(idx) { mutableListOf() }.add(rssi[i])
        }
        val bins = buckets.entries
            .map { (idx, vals) ->
                SweepBin(
                    centerDeg = (idx + 0.5) * SECTOR_DEG,
                    medianRssi = median(vals),
                    count = vals.size,
                )
            }
            .sortedBy { it.centerDeg }

        // Need at least 3 distinct sectors to fit 3 parameters.
        if (bins.size < 3) {
            return SweepResult(null, 0.0, rssi.toList().averageOrNaN(), 0.0, 0.0, bins)
        }

        // Normal equations for y = a + c·cosθ + d·sinθ.
        var s1 = 0.0; var sc = 0.0; var ss = 0.0
        var scc = 0.0; var sss = 0.0; var scs = 0.0
        var sy = 0.0; var syc = 0.0; var sys = 0.0
        for (b in bins) {
            val th = b.centerDeg * DEG2RAD
            val c = cos(th); val s = sin(th); val y = b.medianRssi
            s1 += 1; sc += c; ss += s
            scc += c * c; sss += s * s; scs += c * s
            sy += y; syc += y * c; sys += y * s
        }
        val a = arrayOf(
            doubleArrayOf(s1, sc, ss),
            doubleArrayOf(sc, scc, scs),
            doubleArrayOf(ss, scs, sss),
        )
        val rhs = doubleArrayOf(sy, syc, sys)
        val beta = LinAlg.solve(a, rhs)
            ?: return SweepResult(null, 0.0, sy / bins.size, 0.0, 0.0, bins)
        val (aCoef, cCoef, dCoef) = Triple(beta[0], beta[1], beta[2])
        val amplitude = hypot(cCoef, dCoef)
        val phi = ((atan2(dCoef, cCoef) * RAD2DEG) + 360.0) % 360.0

        // R² of the fit against the binned medians.
        val meanY = sy / bins.size
        var ssTot = 0.0; var ssRes = 0.0
        for (b in bins) {
            val th = b.centerDeg * DEG2RAD
            val pred = aCoef + cCoef * cos(th) + dCoef * sin(th)
            ssRes += (b.medianRssi - pred).let { it * it }
            ssTot += (b.medianRssi - meanY).let { it * it }
        }
        val r2 = if (ssTot < 1e-9) 0.0 else (1.0 - ssRes / ssTot).coerceIn(0.0, 1.0)

        // Confidence folds goodness-of-fit with a saturating depth term.
        val depthTerm = amplitude / (amplitude + DEPTH_HALF_DB)
        val confidence = (r2 * depthTerm).coerceIn(0.0, 1.0)
        val bearing = if (confidence >= MIN_CONFIDENCE) phi else null

        return SweepResult(bearing, amplitude, aCoef, r2, confidence, bins)
    }

    private fun List<Double>.averageOrNaN(): Double = if (isEmpty()) Double.NaN else average()
}
