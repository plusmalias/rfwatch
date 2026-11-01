package com.rfwatch.core.cs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.random.Random

class UltrasonicDetectorTest {

    private val sr = 48_000
    private val rnd = Random(7)

    /** [seconds] of low background noise plus tones: each (freq, amplitude, on(tSeconds)). */
    private fun signal(seconds: Double, vararg tones: Triple<Double, Double, (Double) -> Boolean>): FloatArray {
        val n = (seconds * sr).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / sr
            var v = (rnd.nextDouble() - 0.5) * 0.002
            tones.forEach { (f, a, on) -> if (on(t)) v += a * sin(2 * PI * f * t) }
            v.toFloat()
        }
    }

    private fun run(x: FloatArray): UltrasonicResult = UltrasonicDetector(sr).apply { feed(x) }.result()

    @Test fun quietRoomHasNoTones() {
        val r = run(signal(5.0))
        assertTrue(r.tones.isEmpty())
        assertFalse(r.beaconLike)
    }

    @Test fun speechBandOnlyIsIgnored() {
        val r = run(signal(5.0, Triple(1_000.0, 0.3, { _: Double -> true }), Triple(3_200.0, 0.2, { _: Double -> true })))
        assertTrue(r.tones.isEmpty())
    }

    @Test fun steadyWhineIsSteadyNotBeacon() {
        val r = run(signal(5.0, Triple(20_000.0, 0.01, { _: Double -> true })))
        assertEquals(1, r.tones.size)
        assertEquals(ToneKind.STEADY, r.tones[0].kind)
        assertTrue(abs(r.tones[0].freqHz - 20_000) < 30)
        assertFalse(r.beaconLike)
    }

    @Test fun loudSteadyToneWithFaintSpursIsNotABeacon() {
        // On-device finding: a loud steady tone plus faint flickering spurs nearby (distortion).
        val r = run(
            signal(
                6.0,
                Triple(19_000.0, 0.3, { _: Double -> true }),
                Triple(18_850.0, 0.0006, { t: Double -> (t % 0.37) < 0.08 }),
                Triple(19_440.0, 0.0006, { t: Double -> (t % 0.29) < 0.06 }),
            ),
        )
        assertFalse(r.summary(), r.beaconLike)
        assertEquals(r.summary(), listOf(ToneKind.STEADY), r.tones.map { it.kind })
    }

    @Test fun burstsAreBeaconLike() {
        // 19 kHz, 200 ms on / 400 ms off.
        val r = run(signal(6.0, Triple(19_000.0, 0.01, { t: Double -> (t % 0.6) < 0.2 })))
        assertTrue(r.summary(), r.beaconLike)
        assertTrue(r.tones[0].bursts >= 5)
    }

    @Test fun alternatingTonesAreDataLike() {
        // FSK-ish: 18.5 / 19.0 kHz alternate every 300 ms.
        val r = run(
            signal(
                6.0,
                Triple(18_500.0, 0.01, { t: Double -> ((t / 0.3).toInt() % 2) == 0 }),
                Triple(19_000.0, 0.01, { t: Double -> ((t / 0.3).toInt() % 2) == 1 }),
            ),
        )
        assertTrue(r.summary(), r.alternatingGroup)
        assertTrue(r.beaconLike)
        assertEquals(2, r.tones.size)
    }

    @Test fun singleClickIsNotATone() {
        val x = signal(5.0)
        for (i in 100_000 until 100_050) x[i] = if (i % 2 == 0) 0.9f else -0.9f
        assertFalse(run(x).beaconLike)
    }

    @Test fun fftFindsBin() {
        val n = 1024
        val re = DoubleArray(n) { sin(2 * PI * 64 * it / n) }
        val im = DoubleArray(n)
        UltrasonicDetector.fft(re, im)
        val mags = DoubleArray(n / 2) { re[it] * re[it] + im[it] * im[it] }
        assertEquals(64, mags.indices.maxByOrNull { mags[it] })
    }
}
