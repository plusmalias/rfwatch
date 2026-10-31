package com.rfwatch.app.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A distinct device we have seen. The primary key is a stable synthetic id: for devices
 * with a non-rotating MAC it is the MAC; for rotating trackers it is a linked-identity id
 * assigned by [com.rfwatch.core.cs.TrackerIdentityLinker]. Classification and the
 * allow/own flags feed follower exclusions.
 */
@Entity(tableName = "devices")
data class DeviceEntity(
    @PrimaryKey val id: String,
    val band: String,
    val displayName: String?,
    val lastMac: String?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val lastRssi: Int,
    val classification: String = "UNKNOWN",
    val trackerType: String? = null,
    val mobility: String = "UNKNOWN",
    val isAllowlisted: Boolean = false,
    val isOwnDevice: Boolean = false,
    val ownDeviceConfirmed: Boolean = false,
)

/** One RSSI observation, GPS- and heading-tagged where available. */
@Entity(
    tableName = "observations",
    indices = [Index("deviceId"), Index("timestampMs")],
)
data class ObservationEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val deviceId: String,
    val band: String,
    val rssi: Int,
    val timestampMs: Long,
    val lat: Double? = null,
    val lon: Double? = null,
    val gpsAccuracyM: Double? = null,
    val headingDeg: Double? = null,
    val rttDistanceM: Double? = null,
    val rttStdM: Double? = null,
    val sessionId: Long? = null,
)

/** An emitted alert, retained for the event log and the timeline view. */
@Entity(tableName = "alerts", indices = [Index("timestampMs")])
data class AlertEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val type: String,
    val timestampMs: Long,
    val deviceId: String?,
    val message: String,
    val bearingDeg: Double? = null,
    val confidence: Double = 0.0,
    val acknowledged: Boolean = false,
)

/** A generic, exportable event-log row (sweeps, follower hits, baseline anomalies, …). */
@Entity(tableName = "events", indices = [Index("timestampMs")])
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    val kind: String,
    val timestampMs: Long,
    val deviceId: String? = null,
    val detail: String,
    val lat: Double? = null,
    val lon: Double? = null,
)

/** A user-named geofence (Home, Work) for baseline learning. */
@Entity(tableName = "geofences")
data class GeofenceEntity(
    @PrimaryKey val id: String,
    val name: String,
    val lat: Double,
    val lon: Double,
    val radiusM: Double,
)

/** Per-geofence baseline: how many sessions each device has been present for. */
@Entity(tableName = "baselines", primaryKeys = ["geofenceId", "deviceId"])
data class BaselineEntity(
    val geofenceId: String,
    val deviceId: String,
    val sessionsPresent: Int,
)

/** A monitoring session (used for own-device learning and baseline session counts). */
@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startMs: Long,
    val endMs: Long? = null,
    val geofenceId: String? = null,
)
