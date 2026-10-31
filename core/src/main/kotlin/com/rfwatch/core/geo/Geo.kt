package com.rfwatch.core.geo

import com.rfwatch.core.model.Enu
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Local tangent-plane (ENU) conversions anchored at a reference lat/lon. Over the
 * ~1 km scales used for drive-by localisation an equirectangular projection about the
 * reference latitude is accurate to well under a metre, and it is cheap and invertible.
 *
 * Longitude metres shrink by cos(lat), which is why east uses cos(refLat).
 */
class LocalFrame(val refLatDeg: Double, val refLonDeg: Double) {

    private val cosRef = cos(refLatDeg * DEG2RAD)

    /** Project a WGS84 coordinate into the local ENU plane (metres). */
    fun toEnu(latDeg: Double, lonDeg: Double): Enu {
        val north = (latDeg - refLatDeg) * DEG2RAD * EARTH_R
        val east = (lonDeg - refLonDeg) * DEG2RAD * EARTH_R * cosRef
        return Enu(east, north)
    }

    /** Inverse of [toEnu]: ENU metres back to WGS84 degrees. */
    fun toLatLon(east: Double, north: Double): Pair<Double, Double> {
        val lat = refLatDeg + (north / EARTH_R) / DEG2RAD
        val lon = refLonDeg + (east / (EARTH_R * cosRef)) / DEG2RAD
        return lat to lon
    }

    companion object {
        /** WGS84 mean radius (m). */
        const val EARTH_R = 6_371_008.8
        const val DEG2RAD = PI / 180.0
        const val RAD2DEG = 180.0 / PI
    }
}

/**
 * Bearing (degrees clockwise from north, 0..360) from an ENU origin to a target point.
 * East is +x, north is +y, so bearing = atan2(east, north).
 */
fun bearingDeg(east: Double, north: Double): Double {
    val b = atan2(east, north) * LocalFrame.RAD2DEG
    return (b + 360.0) % 360.0
}

/** Planar distance (m) in the ENU frame. */
fun distanceM(east: Double, north: Double): Double = hypot(east, north)

/**
 * Great-circle distance (m) between two WGS84 points via the haversine formula. Used for
 * follower scoring where points may be kilometres apart and the flat-plane approximation
 * no longer holds.
 */
fun haversineM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
    val dLat = (lat2 - lat1) * LocalFrame.DEG2RAD
    val dLon = (lon2 - lon1) * LocalFrame.DEG2RAD
    val a = sin(dLat / 2).let { it * it } +
        cos(lat1 * LocalFrame.DEG2RAD) * cos(lat2 * LocalFrame.DEG2RAD) *
        sin(dLon / 2).let { it * it }
    return 2 * LocalFrame.EARTH_R * atan2(sqrt(a), sqrt(1 - a))
}

/** Smallest absolute difference between two bearings, in degrees (0..180). */
fun angularDiffDeg(a: Double, b: Double): Double {
    var d = ((a - b) % 360.0 + 540.0) % 360.0 - 180.0
    if (d < -180.0) d += 360.0
    return kotlin.math.abs(d)
}
