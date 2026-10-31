package com.rfwatch.core.df

import com.rfwatch.core.math.LinAlg
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** One drive-by sample already projected into the local ENU plane. */
data class EnuSample(
    val east: Double,
    val north: Double,
    val rssi: Double,
    /** Optional Wi-Fi RTT range (m) from this location to the emitter. */
    val rttDistanceM: Double? = null,
    val rttStdM: Double? = null,
)

/** A 2-σ error ellipse in the ENU plane. */
data class ErrorEllipse(
    val semiMajorM: Double,
    val semiMinorM: Double,
    /** Orientation of the major axis, degrees clockwise from north. */
    val orientationDeg: Double,
)

/** Result of a path-loss fit in the ENU plane. */
data class LocalizationFit(
    val east: Double,
    val north: Double,
    val p0: Double,
    val n: Double,
    /** 2×2 position covariance [[σxx,σxy],[σxy,σyy]] in m². */
    val covXY: Array<DoubleArray>,
    val ellipse: ErrorEllipse,
    val rmsResidualDb: Double,
    val iterations: Int,
    val converged: Boolean,
    /** True when the LM fit failed and the weighted centroid was returned instead. */
    val usedFallback: Boolean,
) {
    override fun equals(other: Any?) = this === other
    override fun hashCode() = System.identityHashCode(this)
}

/**
 * Estimates an emitter's position from GPS-tagged RSSI samples using the log-distance
 * path-loss model `rssi = P0 − 10·n·log10(d)`, solved by Levenberg–Marquardt over the
 * four unknowns (east, north, P0, n) in a local ENU frame. The path-loss exponent `n`
 * is clamped to [N_MIN]..[N_MAX] to keep the fit physically plausible.
 *
 * Wi-Fi RTT ranges, when present, enter as additional hard residuals `d − rttDistance`
 * weighted by 1/σ², pinning the geometry far more tightly than RSSI alone can.
 *
 * The RSSI-weighted centroid seeds the solver and is also the fallback when the fit fails
 * to converge or produces a degenerate covariance.
 */
object PathLossLocalizer {

    const val N_MIN = 1.8
    const val N_MAX = 4.0
    private const val N_INIT = 2.7
    private const val MAX_ITER = 100
    private const val LN10_OVER_10 = 0.23025850929940458 // ln(10)/10

    /** Standard deviation (dB) assumed for an RSSI residual when weighting against RTT. */
    private const val RSSI_SIGMA_DB = 4.0

    fun locate(samples: List<EnuSample>): LocalizationFit {
        require(samples.size >= 3) { "need at least 3 samples to fit 4 parameters (RSSI) " +
            "— gating should enforce 8" }

        val centroid = weightedCentroid(samples)
        var x = centroid.first
        var y = centroid.second
        var p0 = samples.maxOf { it.rssi } + 5.0 // strongest sample ≈ closest approach
        var n = N_INIT

        var lambda = 1e-3
        var prevCost = cost(samples, x, y, p0, n)
        var converged = false
        var iter = 0

        while (iter < MAX_ITER) {
            iter++
            val (jtj, jtr) = normalEquations(samples, x, y, p0, n)
            // Levenberg–Marquardt damping on the diagonal.
            val damped = Array(4) { i -> DoubleArray(4) { j ->
                jtj[i][j] + if (i == j) lambda * jtj[i][j] else 0.0
            } }
            val delta = LinAlg.solve(damped, jtr.map { -it }.toDoubleArray())
            if (delta == null) {
                lambda *= 10
                if (lambda > 1e7) break
                continue
            }
            val nx = x + delta[0]
            val ny = y + delta[1]
            val np0 = p0 + delta[2]
            val nn = (n + delta[3]).coerceIn(N_MIN, N_MAX)
            val newCost = cost(samples, nx, ny, np0, nn)
            if (newCost < prevCost) {
                val rel = (prevCost - newCost) / prevCost.coerceAtLeast(1e-9)
                x = nx; y = ny; p0 = np0; n = nn
                lambda = (lambda / 3).coerceAtLeast(1e-9)
                prevCost = newCost
                if (rel < 1e-6 || hypot(delta[0], delta[1]) < 1e-3) {
                    converged = true
                    break
                }
            } else {
                lambda *= 3
                if (lambda > 1e7) break
            }
        }

        val dof = (samples.size + samples.count { it.rttDistanceM != null } - 4).coerceAtLeast(1)
        val rms = sqrt(prevCost / dof)

        // Covariance = residual-variance × (JᵀJ)⁻¹ over position block.
        val (jtj, _) = normalEquations(samples, x, y, p0, n)
        val inv = LinAlg.inverse(jtj)
        val sigma2 = (prevCost / dof).coerceAtLeast(1e-6)
        val cov = if (inv == null) {
            null
        } else {
            arrayOf(
                doubleArrayOf(inv[0][0] * sigma2, inv[0][1] * sigma2),
                doubleArrayOf(inv[1][0] * sigma2, inv[1][1] * sigma2),
            )
        }

        if (cov == null || !cov.isFinitePosDef()) {
            // Degenerate fit → fall back to the weighted centroid with a coarse circular
            // uncertainty from the spatial spread of samples.
            val spread = sampleSpread(samples)
            val fallbackCov = arrayOf(
                doubleArrayOf(spread * spread, 0.0),
                doubleArrayOf(0.0, spread * spread),
            )
            return LocalizationFit(
                east = centroid.first, north = centroid.second, p0 = p0, n = n,
                covXY = fallbackCov, ellipse = ellipseFrom(fallbackCov),
                rmsResidualDb = rms, iterations = iter, converged = false, usedFallback = true,
            )
        }

        return LocalizationFit(
            east = x, north = y, p0 = p0, n = n,
            covXY = cov, ellipse = ellipseFrom(cov),
            rmsResidualDb = rms, iterations = iter, converged = converged, usedFallback = false,
        )
    }

    /** RSSI-weighted centroid, weighting by linear received power 10^(rssi/10). */
    fun weightedCentroid(samples: List<EnuSample>): Pair<Double, Double> {
        var sw = 0.0; var sx = 0.0; var sy = 0.0
        for (s in samples) {
            val w = Math.pow(10.0, s.rssi / 10.0)
            sw += w; sx += w * s.east; sy += w * s.north
        }
        if (sw < 1e-30) {
            return samples.map { it.east }.average() to samples.map { it.north }.average()
        }
        return (sx / sw) to (sy / sw)
    }

    private fun cost(samples: List<EnuSample>, x: Double, y: Double, p0: Double, n: Double): Double {
        var c = 0.0
        val wRssi = 1.0 / (RSSI_SIGMA_DB * RSSI_SIGMA_DB)
        for (s in samples) {
            val d = hypot(x - s.east, y - s.north).coerceAtLeast(0.5)
            val pred = p0 - 10.0 * n * log10(d)
            val r = s.rssi - pred
            c += wRssi * r * r
            if (s.rttDistanceM != null) {
                val sig = (s.rttStdM ?: 2.0).coerceAtLeast(0.3)
                val wr = 1.0 / (sig * sig)
                val rr = d - s.rttDistanceM
                c += wr * rr * rr
            }
        }
        return c
    }

    /** Returns (JᵀWJ, JᵀWr) for the current parameter vector. */
    private fun normalEquations(
        samples: List<EnuSample>, x: Double, y: Double, p0: Double, n: Double,
    ): Pair<Array<DoubleArray>, DoubleArray> {
        val jtj = Array(4) { DoubleArray(4) }
        val jtr = DoubleArray(4)
        val wRssi = 1.0 / (RSSI_SIGMA_DB * RSSI_SIGMA_DB)
        for (s in samples) {
            val dx = x - s.east
            val dy = y - s.north
            val d = hypot(dx, dy).coerceAtLeast(0.5)
            val pred = p0 - 10.0 * n * log10(d)
            val r = s.rssi - pred
            // ∂r/∂x, ∂r/∂y, ∂r/∂P0, ∂r/∂n
            val k = 10.0 * n / (ln(10.0) * d * d)
            val j = doubleArrayOf(k * dx, k * dy, -1.0, 10.0 * log10(d))
            accumulate(jtj, jtr, j, r, wRssi)
            if (s.rttDistanceM != null) {
                val sig = (s.rttStdM ?: 2.0).coerceAtLeast(0.3)
                val wr = 1.0 / (sig * sig)
                val rr = d - s.rttDistanceM
                val jr = doubleArrayOf(dx / d, dy / d, 0.0, 0.0)
                accumulate(jtj, jtr, jr, rr, wr)
            }
        }
        return jtj to jtr
    }

    private fun accumulate(
        jtj: Array<DoubleArray>, jtr: DoubleArray, j: DoubleArray, r: Double, w: Double,
    ) {
        for (a in 0 until 4) {
            jtr[a] += w * j[a] * r
            for (b in 0 until 4) jtj[a][b] += w * j[a] * j[b]
        }
    }

    private fun sampleSpread(samples: List<EnuSample>): Double {
        val cx = samples.map { it.east }.average()
        val cy = samples.map { it.north }.average()
        return sqrt(samples.sumOf { (it.east - cx) * (it.east - cx) + (it.north - cy) * (it.north - cy) } / samples.size)
    }

    private fun Array<DoubleArray>.isFinitePosDef(): Boolean {
        val a = this[0][0]; val b = this[0][1]; val c = this[1][1]
        if (!a.isFinite() || !b.isFinite() || !c.isFinite()) return false
        return a > 0 && (a * c - b * b) > 0
    }

    /** Eigen-decompose the 2×2 covariance into a 2-σ ellipse. */
    fun ellipseFrom(cov: Array<DoubleArray>): ErrorEllipse {
        val a = cov[0][0]; val b = cov[0][1]; val c = cov[1][1]
        val tr = a + c
        val det = a * c - b * b
        val disc = sqrt((tr * tr / 4 - det).coerceAtLeast(0.0))
        val l1 = tr / 2 + disc
        val l2 = (tr / 2 - disc).coerceAtLeast(0.0)
        // Major-axis orientation. Eigenvector of l1.
        val angleRad = if (abs(b) < 1e-12) {
            if (a >= c) 0.0 else Math.PI / 2
        } else {
            atan2(l1 - a, b)
        }
        // Convert math-plane angle (east=x, north=y) to compass bearing (CW from north).
        val eastComp = cos(angleRad)
        val northComp = sin(angleRad)
        val bearing = ((atan2(eastComp, northComp) * 180.0 / Math.PI) + 360.0) % 360.0
        return ErrorEllipse(
            semiMajorM = 2.0 * sqrt(l1),
            semiMinorM = 2.0 * sqrt(l2),
            orientationDeg = bearing,
        )
    }
}
