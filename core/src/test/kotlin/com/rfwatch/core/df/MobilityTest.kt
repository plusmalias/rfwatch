package com.rfwatch.core.df

import com.rfwatch.core.model.Band
import com.rfwatch.core.model.RadioObservation
import java.util.Random
import kotlin.math.hypot
import kotlin.math.log10
import org.junit.Assert.assertEquals
import org.junit.Test

class MobilityTest {

    @Test
    fun thresholdsClassifyDirectly() {
        assertEquals(MobilityClass.STATIONARY, Mobility.classify(3.0, null))
        assertEquals(MobilityClass.MOBILE, Mobility.classify(12.0, null))
        assertEquals(MobilityClass.MOBILE, Mobility.classify(3.0, 200.0))
    }

    @Test
    fun stationaryEmitterFitsLowResidual() {
        val rng = Random(1)
        val ex = 10.0; val en = -20.0
        val samples = (0 until 20).map {
            val e = -150.0 + it * 15.0
            val nth = if (it < 10) -70.0 else 60.0
            val d = hypot(e - ex, nth - en).coerceAtLeast(1.0)
            EnuSample(e, nth, -40.0 - 10 * 2.7 * log10(d) + rng.nextGaussian() * 3.0)
        }
        val fit = PathLossLocalizer.locate(samples)
        assertEquals(MobilityClass.STATIONARY, Mobility.classify(fit.rmsResidualDb, null))
    }

    @Test
    fun movingEmitterRaisesResidual() {
        // Emitter slides 8 m per sample; no single position can explain the RSSI, so the
        // fit residual is large and the classifier should call it MOBILE.
        val rng = Random(2)
        val samples = (0 until 20).map {
            val userE = -150.0 + it * 15.0
            val emitterE = it * 8.0
            val d = hypot(userE - emitterE, 70.0).coerceAtLeast(1.0)
            EnuSample(userE, -70.0, -40.0 - 10 * 2.7 * log10(d) + rng.nextGaussian() * 2.0)
        }
        val fit = PathLossLocalizer.locate(samples)
        assertEquals(MobilityClass.MOBILE, Mobility.classify(fit.rmsResidualDb, null))
    }

    @Test
    fun risingRssiIsApproaching() {
        val obs = (0 until 30).map {
            RadioObservation("x", Band.BLE, rssi = -90 + it, timestampMs = it * 2000L)
        }
        assertEquals(RssiTrend.APPROACHING, Mobility.trend(obs, nowMs = 58_000L))
    }

    @Test
    fun fallingRssiIsReceding() {
        val obs = (0 until 30).map {
            RadioObservation("x", Band.BLE, rssi = -50 - it, timestampMs = it * 2000L)
        }
        assertEquals(RssiTrend.RECEDING, Mobility.trend(obs, nowMs = 58_000L))
    }

    @Test
    fun flatRssiIsSteady() {
        val rng = Random(3)
        val obs = (0 until 30).map {
            RadioObservation("x", Band.BLE, rssi = -70 + rng.nextInt(3) - 1, timestampMs = it * 2000L)
        }
        assertEquals(RssiTrend.STEADY, Mobility.trend(obs, nowMs = 58_000L))
    }
}
