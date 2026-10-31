package com.rfwatch.core.math

import kotlin.math.sqrt

/**
 * Exponentially-weighted moving average with a time constant expressed in milliseconds,
 * so irregular sample spacing (BLE advertisements arrive unevenly) is handled correctly:
 * the decay is exp(-dt / tau) rather than a fixed per-sample alpha.
 */
class TimeEwma(private val tauMs: Double) {
    private var value: Double = Double.NaN
    private var lastMs: Long = 0

    val current: Double get() = value

    fun update(x: Double, tMs: Long): Double {
        value = if (value.isNaN()) {
            x
        } else {
            val dt = (tMs - lastMs).coerceAtLeast(0).toDouble()
            val a = 1.0 - Math.exp(-dt / tauMs)
            value + a * (x - value)
        }
        lastMs = tMs
        return value
    }

    fun reset() {
        value = Double.NaN
        lastMs = 0
    }
}

/** Ordinary least-squares line y = slope·x + intercept, plus R². */
data class LineFit(val slope: Double, val intercept: Double, val r2: Double, val n: Int)

/**
 * Fit a line to (x, y) points by ordinary least squares. Returns null for fewer than two
 * points or zero variance in x. Used for the RSSI-trend (approaching/receding) classifier.
 */
fun fitLine(xs: DoubleArray, ys: DoubleArray): LineFit? {
    val n = xs.size
    if (n < 2 || ys.size != n) return null
    val mx = xs.average()
    val my = ys.average()
    var sxx = 0.0
    var sxy = 0.0
    var syy = 0.0
    for (i in 0 until n) {
        val dx = xs[i] - mx
        val dy = ys[i] - my
        sxx += dx * dx
        sxy += dx * dy
        syy += dy * dy
    }
    if (sxx < 1e-12) return null
    val slope = sxy / sxx
    val intercept = my - slope * mx
    val r2 = if (syy < 1e-12) 1.0 else (sxy * sxy) / (sxx * syy)
    return LineFit(slope, intercept, r2, n)
}

/** Median of a double list (linear-interpolated for even counts). Empty → NaN. */
fun median(values: List<Double>): Double {
    if (values.isEmpty()) return Double.NaN
    val s = values.sorted()
    val mid = s.size / 2
    return if (s.size % 2 == 1) s[mid] else (s[mid - 1] + s[mid]) / 2.0
}

/** Sample standard deviation; fewer than two points → 0. */
fun stdDev(values: List<Double>): Double {
    if (values.size < 2) return 0.0
    val m = values.average()
    val ss = values.sumOf { (it - m) * (it - m) }
    return sqrt(ss / (values.size - 1))
}
