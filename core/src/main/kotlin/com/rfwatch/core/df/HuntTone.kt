package com.rfwatch.core.df

/** Geiger-counter-style audio feedback for Hunt mode. */
object HuntTone {

    private const val MIN_RSSI = -100.0
    private const val MAX_RSSI = -30.0
    private const val MIN_HZ = 200.0
    private const val MAX_HZ = 2000.0

    /** Deadband (dB) within which proximity is unchanged. */
    private const val DEADBAND_DB = 1.5

    enum class Proximity { HOTTER, SAME, COLDER }

    /** Map a (smoothed) RSSI to a tone pitch: stronger signal → higher pitch. */
    fun pitchHz(ewmaRssi: Double): Double {
        val t = ((ewmaRssi - MIN_RSSI) / (MAX_RSSI - MIN_RSSI)).coerceIn(0.0, 1.0)
        return MIN_HZ + t * (MAX_HZ - MIN_HZ)
    }

    /** Pulse repetition rate (Hz) — faster as you get closer, Geiger-style. */
    fun pulseRateHz(ewmaRssi: Double): Double {
        val t = ((ewmaRssi - MIN_RSSI) / (MAX_RSSI - MIN_RSSI)).coerceIn(0.0, 1.0)
        return 1.0 + t * 19.0 // 1..20 Hz
    }

    fun proximity(deltaRssiDb: Double): Proximity = when {
        deltaRssiDb > DEADBAND_DB -> Proximity.HOTTER
        deltaRssiDb < -DEADBAND_DB -> Proximity.COLDER
        else -> Proximity.SAME
    }
}
