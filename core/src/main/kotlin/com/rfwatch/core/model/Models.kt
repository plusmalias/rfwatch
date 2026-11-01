package com.rfwatch.core.model

/** Radio band a device was observed on. */
enum class Band { BLE, WIFI }

/** Source of a heading sample. See [com.rfwatch.core.df.HeadingFusion]. */
enum class HeadingSource { COMPASS, GPS_BEARING, NONE }

/** Android SensorManager accuracy levels, mirrored here so :core stays Android-free. */
enum class SensorAccuracy { UNRELIABLE, LOW, MEDIUM, HIGH, NO_CONTACT }

/**
 * A fused heading in degrees clockwise from true north, with its provenance and the
 * underlying sensor accuracy. [deg] is null when no trustworthy heading is available.
 */
data class Heading(
    val deg: Double?,
    val source: HeadingSource,
    val accuracy: SensorAccuracy,
)

/** A GPS fix in WGS84 with horizontal accuracy (m) and, when moving, course/speed. */
data class GpsFix(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val timestampMs: Long,
    val bearingDeg: Double? = null,
    val speedMps: Double? = null,
    val hasBearing: Boolean = bearingDeg != null,
    val hasSpeed: Boolean = speedMps != null,
    /** Android location provider ("gps", "network", "fused"), when known. */
    val provider: String? = null,
)

/**
 * One RSSI observation of a device, optionally tagged with the user's heading at the
 * instant of the reading (for sweep mode) and/or a GPS fix (for drive-by localisation).
 */
data class RadioObservation(
    val deviceId: String,
    val band: Band,
    val rssi: Int,
    val timestampMs: Long,
    val headingDeg: Double? = null,
    val fix: GpsFix? = null,
    val rttDistanceM: Double? = null,
    val rttStdDevM: Double? = null,
)

/** A point in the local East-North-Up tangent plane (metres). Up is unused (2-D). */
data class Enu(val east: Double, val north: Double)
