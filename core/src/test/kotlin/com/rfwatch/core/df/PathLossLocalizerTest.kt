package com.rfwatch.core.df

import java.util.Random
import kotlin.math.hypot
import kotlin.math.log10
import org.junit.Assert.assertTrue
import org.junit.Test

class PathLossLocalizerTest {

    private fun simulateDrive(
        emitterE: Double, emitterN: Double, p0: Double, n: Double,
        sigma: Double, seed: Long,
    ): List<EnuSample> {
        val rng = Random(seed)
        val pts = ArrayList<Pair<Double, Double>>()
        // L-shaped path so the geometry is 2-D (avoids the across-track mirror ambiguity).
        for (i in 0 until 10) {
            val t = -150.0 + i * (300.0 / 9)
            pts.add(t to -70.0)
        }
        for (i in 0 until 10) {
            val t = -70.0 + i * (190.0 / 9)
            pts.add(150.0 to t)
        }
        return pts.map { (e, nth) ->
            val d = hypot(e - emitterE, nth - emitterN).coerceAtLeast(1.0)
            val clean = p0 - 10.0 * n * log10(d)
            EnuSample(e, nth, clean + rng.nextGaussian() * sigma)
        }
    }

    @Test
    fun positionErrorUnder50mAt20Samples() {
        val ex = 0.0; val en = 0.0
        val fit = PathLossLocalizer.locate(
            simulateDrive(ex, en, p0 = -40.0, n = 2.7, sigma = 4.0, seed = 123)
        )
        val err = hypot(fit.east - ex, fit.north - en)
        assertTrue("position error $err m should be < 50 m (fallback=${fit.usedFallback})", err < 50.0)
        assertTrue("n should stay in physical range", fit.n in PathLossLocalizer.N_MIN..PathLossLocalizer.N_MAX)
    }

    @Test
    fun errorEllipseContainsTruth() {
        val ex = 0.0; val en = 0.0
        val fit = PathLossLocalizer.locate(
            simulateDrive(ex, en, p0 = -40.0, n = 2.7, sigma = 4.0, seed = 99)
        )
        // Truth should lie within the 2σ ellipse: Mahalanobis distance ≤ 2.
        val inv = com.rfwatch.core.math.LinAlg.inverse(fit.covXY)!!
        val dx = ex - fit.east
        val dy = en - fit.north
        val maha = dx * (inv[0][0] * dx + inv[0][1] * dy) + dy * (inv[1][0] * dx + inv[1][1] * dy)
        assertTrue("Mahalanobis² $maha should be ≤ 4 (2σ)", maha <= 4.0)
    }

    @Test
    fun rttConstraintsTightenFit() {
        val ex = 20.0; val en = 30.0
        val base = simulateDrive(ex, en, p0 = -40.0, n = 2.7, sigma = 6.0, seed = 5)
        val withRtt = base.map {
            val d = hypot(it.east - ex, it.north - en)
            it.copy(rttDistanceM = d + (Random(it.hashCode().toLong()).nextGaussian() * 1.0), rttStdM = 1.0)
        }
        val plain = PathLossLocalizer.locate(base)
        val ranged = PathLossLocalizer.locate(withRtt)
        val ePlain = hypot(plain.east - ex, plain.north - en)
        val eRanged = hypot(ranged.east - ex, ranged.north - en)
        assertTrue("RTT fit ($eRanged m) should be at least as good as RSSI-only ($ePlain m)",
            eRanged <= ePlain + 1.0)
    }
}
