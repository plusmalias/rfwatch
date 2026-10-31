package com.rfwatch.core.df

import com.rfwatch.core.math.TimeEwma
import com.rfwatch.core.math.fitLine
import com.rfwatch.core.model.RadioObservation

enum class MobilityClass { STATIONARY, MOBILE }

enum class RssiTrend { APPROACHING, STEADY, RECEDING, UNKNOWN }

/**
 * Decides whether an emitter is stationary (worth a fixed position estimate) or mobile
 * (position meaningless — report a trend instead).
 *
 * Two independent signals mark an emitter MOBILE:
 *  - a high path-loss fit residual ([residualMobileDb]): a moving source cannot be
 *    explained by one fixed (x, y), so the fit stays poor however many samples arrive;
 *  - drift ([driftMobileM]): the re-fitted position jumps between refits by more than a
 *    stationary emitter's uncertainty could account for.
 */
object Mobility {

    const val residualMobileDb = 8.0
    const val driftMobileM = 150.0

    /** Deadband (dB over the window) below which an RSSI trend is called STEADY. */
    private const val TREND_DEADBAND_DB = 3.0
    private const val TREND_EWMA_TAU_MS = 10_000.0
    const val TREND_WINDOW_MS = 60_000L

    fun classify(rmsResidualDb: Double, driftM: Double?): MobilityClass {
        if (rmsResidualDb > residualMobileDb) return MobilityClass.MOBILE
        if (driftM != null && driftM > driftMobileM) return MobilityClass.MOBILE
        return MobilityClass.STATIONARY
    }

    /**
     * Classifies the RSSI trend over the trailing [TREND_WINDOW_MS] using a slope fit of
     * the EWMA-smoothed RSSI against time. Rising power → APPROACHING.
     */
    fun trend(observations: List<RadioObservation>, nowMs: Long): RssiTrend {
        val window = observations
            .filter { nowMs - it.timestampMs <= TREND_WINDOW_MS }
            .sortedBy { it.timestampMs }
        if (window.size < 4) return RssiTrend.UNKNOWN

        val ewma = TimeEwma(TREND_EWMA_TAU_MS)
        val xs = DoubleArray(window.size)
        val ys = DoubleArray(window.size)
        val t0 = window.first().timestampMs
        for (i in window.indices) {
            val o = window[i]
            ys[i] = ewma.update(o.rssi.toDouble(), o.timestampMs)
            xs[i] = (o.timestampMs - t0) / 1000.0
        }
        val fit = fitLine(xs, ys) ?: return RssiTrend.UNKNOWN
        val spanS = xs.last() - xs.first()
        val totalChange = fit.slope * spanS // dB across the whole window
        return when {
            totalChange > TREND_DEADBAND_DB -> RssiTrend.APPROACHING
            totalChange < -TREND_DEADBAND_DB -> RssiTrend.RECEDING
            else -> RssiTrend.STEADY
        }
    }
}
