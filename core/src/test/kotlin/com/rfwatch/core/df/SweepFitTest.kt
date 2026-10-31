package com.rfwatch.core.df

import com.rfwatch.core.geo.angularDiffDeg
import java.util.Random
import kotlin.math.cos
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SweepFitTest {

    private val DEG2RAD = Math.PI / 180.0

    /**
     * Spec target: recover the bearing within 15° at SNR ≥ 6 dB. Here amplitude b = 10 dB
     * and noise σ = 5 dB, i.e. b/σ = 2 (≈ 6 dB), swept over a full 360°.
     */
    @Test
    fun recoversBearingWithin15DegAt6dBSnr() {
        val rng = Random(42)
        val phiTrue = 137.0
        val a = -60.0
        val b = 10.0
        val sigma = 5.0
        val headings = ArrayList<Double>()
        val rssi = ArrayList<Double>()
        var theta = 0.0
        while (theta < 360.0) {
            repeat(2) {
                headings.add(theta)
                val clean = a + b * cos((theta - phiTrue) * DEG2RAD)
                rssi.add(clean + rng.nextGaussian() * sigma)
            }
            theta += 2.0
        }
        val r = SweepFit.fit(headings.toDoubleArray(), rssi.toDoubleArray())
        assertTrue("confidence should be high, was ${r.confidence}", r.confidence > SweepFit.MIN_CONFIDENCE)
        assertTrue("bearing should be present", r.bearingDeg != null)
        val err = angularDiffDeg(r.bearingDeg!!, phiTrue)
        assertTrue("bearing error $err° should be < 15°", err < 15.0)
    }

    @Test
    fun noLobeOnPureNoiseGivesNoDirection() {
        val rng = Random(7)
        val headings = ArrayList<Double>()
        val rssi = ArrayList<Double>()
        var theta = 0.0
        while (theta < 360.0) {
            headings.add(theta)
            rssi.add(-70.0 + rng.nextGaussian() * 6.0) // no directional component
            theta += 5.0
        }
        val r = SweepFit.fit(headings.toDoubleArray(), rssi.toDoubleArray())
        assertTrue("confidence should be low, was ${r.confidence}", r.confidence < SweepFit.MIN_CONFIDENCE)
        assertNull("no clear direction", r.bearingDeg)
    }
}
