package com.rfwatch.core.df

import com.rfwatch.core.model.GpsFix
import com.rfwatch.core.model.Heading
import com.rfwatch.core.model.HeadingSource
import com.rfwatch.core.model.SensorAccuracy

/**
 * Pure decision logic for fusing a magnetic-compass heading with GPS course. The Android
 * layer ([com.rfwatch.app] HeadingProvider) feeds raw sensor values in; this object owns
 * the policy so it can be unit-tested on a bare JVM.
 *
 * Policy, in order:
 *  1. When moving faster than [SPEED_GPS_THRESHOLD_MPS] and a GPS bearing exists, trust
 *     the GPS course — a compass inside a moving vehicle is dominated by the car's own
 *     ferrous mass and electronics.
 *  2. When the magnetometer looks disturbed — reported accuracy below HIGH, or ambient
 *     field magnitude outside the plausible geomagnetic range of
 *     [FIELD_MIN_UT]..[FIELD_MAX_UT] µT — fall back to GPS bearing if one is available,
 *     otherwise report no heading rather than a wrong one.
 *  3. Otherwise use the compass, rotated from magnetic to true north by [declinationDeg].
 */
object HeadingFusion {

    const val SPEED_GPS_THRESHOLD_MPS = 3.0
    const val FIELD_MIN_UT = 25.0
    const val FIELD_MAX_UT = 65.0

    fun fuse(
        magneticHeadingDeg: Double?,
        declinationDeg: Double,
        accuracy: SensorAccuracy,
        fieldMagnitudeUT: Double?,
        gps: GpsFix?,
    ): Heading {
        val gpsBearing = gps?.let { if (it.hasBearing) it.bearingDeg else null }
        val speed = gps?.speedMps ?: 0.0
        val moving = gps?.hasSpeed == true && speed > SPEED_GPS_THRESHOLD_MPS

        // 1. Moving fast → prefer GPS course.
        if (moving && gpsBearing != null) {
            return Heading(norm(gpsBearing), HeadingSource.GPS_BEARING, SensorAccuracy.HIGH)
        }

        // 2. Magnetometer disturbed?
        val fieldBad = fieldMagnitudeUT != null &&
            (fieldMagnitudeUT < FIELD_MIN_UT || fieldMagnitudeUT > FIELD_MAX_UT)
        val accuracyBad = accuracy != SensorAccuracy.HIGH
        val disturbed = fieldBad || accuracyBad
        if (disturbed) {
            return if (gpsBearing != null) {
                Heading(norm(gpsBearing), HeadingSource.GPS_BEARING, SensorAccuracy.HIGH)
            } else {
                Heading(null, HeadingSource.NONE, accuracy)
            }
        }

        // 3. Trust the compass; convert magnetic → true north.
        return if (magneticHeadingDeg != null) {
            Heading(norm(magneticHeadingDeg + declinationDeg), HeadingSource.COMPASS, accuracy)
        } else if (gpsBearing != null) {
            Heading(norm(gpsBearing), HeadingSource.GPS_BEARING, SensorAccuracy.HIGH)
        } else {
            Heading(null, HeadingSource.NONE, accuracy)
        }
    }

    private fun norm(d: Double): Double = ((d % 360.0) + 360.0) % 360.0
}
