package com.rfwatch.app.scan

import com.rfwatch.core.model.GpsFix

/**
 * Thread-safe holder for the most recent location fix, shared across scan collectors.
 *
 * GPS and Android's network location both feed it. A network fix (often ±100 m) arriving between
 * GPS fixes (±2–5 m, every second) used to replace the good one, so ~2 % of sightings were stamped
 * with a rough position. Now a non-GPS fix is ignored while a GPS fix that arrived within
 * [GPS_FRESH_MS] is at least as accurate. Freshness uses arrival time (monotonic clock), not the
 * fix's own timestamp. Without fresh GPS (indoors, car parks) network fixes are used as before.
 */
class LocationFixHolder(private val clock: () -> Long = { android.os.SystemClock.elapsedRealtime() }) {
    @Volatile var current: GpsFix? = null
        private set
    @Volatile private var gpsArrivedAt = Long.MIN_VALUE / 2

    @Synchronized
    fun update(fix: GpsFix) {
        val now = clock()
        if (fix.provider == GPS) {
            gpsArrivedAt = now
            current = fix
            return
        }
        val held = current
        val gpsFresh = held?.provider == GPS && now - gpsArrivedAt <= GPS_FRESH_MS
        if (gpsFresh && held!!.accuracyM <= fix.accuracyM) return
        current = fix
    }

    companion object {
        const val GPS = "gps"
        /** At 100 km/h a 5 s old GPS fix is ~140 m behind; GPS normally updates every second. */
        const val GPS_FRESH_MS = 5_000L
    }
}
