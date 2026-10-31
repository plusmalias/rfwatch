package com.rfwatch.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface DeviceDao {
    @Upsert suspend fun upsert(device: DeviceEntity)
    @Upsert suspend fun upsertAll(devices: List<DeviceEntity>)
    @Query("SELECT * FROM devices") suspend fun all(): List<DeviceEntity>
    // Keep rows in discovery order: lastSeenMs changes on every scan and makes a live list jump under taps.
    @Query("SELECT * FROM devices ORDER BY firstSeenMs DESC, id ASC") fun observeAll(): Flow<List<DeviceEntity>>
    @Query("SELECT * FROM devices WHERE id = :id") suspend fun byId(id: String): DeviceEntity?
    @Query("SELECT * FROM devices WHERE observedLat IS NOT NULL AND observedLon IS NOT NULL ORDER BY lastSeenMs DESC LIMIT :limit")
    suspend fun recentMapped(limit: Int): List<DeviceEntity>
    @Query("UPDATE devices SET isAllowlisted = :allow WHERE id = :id") suspend fun setAllowlisted(id: String, allow: Boolean)
    @Query("UPDATE devices SET isOwnDevice = :own, ownDeviceConfirmed = :confirmed WHERE id = :id")
    suspend fun setOwnDevice(id: String, own: Boolean, confirmed: Boolean)
    @Query("SELECT id FROM devices WHERE isAllowlisted = 1") suspend fun allowlistedIds(): List<String>
    @Query("SELECT id FROM devices WHERE isOwnDevice = 1") suspend fun ownDeviceIds(): List<String>
}

@Dao
interface ObservationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(obs: ObservationEntity): Long
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertAll(obs: List<ObservationEntity>)
    @Query("SELECT * FROM observations WHERE deviceId = :id ORDER BY timestampMs") suspend fun forDevice(id: String): List<ObservationEntity>
    @Query("SELECT * FROM observations WHERE deviceId = :id ORDER BY timestampMs DESC, rowId DESC LIMIT :limit")
    suspend fun recentForDevice(id: String, limit: Int = 60): List<ObservationEntity>
    @Query("SELECT * FROM observations WHERE deviceId = :id AND lat IS NOT NULL ORDER BY timestampMs") suspend fun geoForDevice(id: String): List<ObservationEntity>
    @Query("SELECT * FROM observations WHERE deviceId = :id AND lat IS NOT NULL AND lon IS NOT NULL ORDER BY timestampMs DESC, rowId DESC LIMIT :limit")
    suspend fun recentGeoForDevice(id: String, limit: Int = 500): List<ObservationEntity>
    @Query("SELECT * FROM observations WHERE lat IS NOT NULL AND lon IS NOT NULL ORDER BY timestampMs DESC, rowId DESC LIMIT :limit")
    suspend fun recentGeoTrack(limit: Int): List<ObservationEntity>
    @Query("SELECT COUNT(*) FROM observations WHERE sessionId = :sessionId")
    suspend fun countForSession(sessionId: Long): Int
    @Query("DELETE FROM observations WHERE timestampMs < :cutoff") suspend fun purgeOlderThan(cutoff: Long): Int
    /** Devices with enough GPS-tagged hearings since [since], spread over at least [minSpanMs] — follower candidates. */
    @Query(
        "SELECT deviceId, COUNT(*) AS hits, MIN(timestampMs) AS firstMs, MAX(timestampMs) AS lastMs FROM observations " +
            "WHERE lat IS NOT NULL AND lon IS NOT NULL AND timestampMs >= :since GROUP BY deviceId " +
            "HAVING hits >= :minHits AND (lastMs - firstMs) >= :minSpanMs ORDER BY hits DESC LIMIT :limit"
    )
    suspend fun followerCandidates(since: Long, minHits: Int, minSpanMs: Long, limit: Int): List<FollowerCandidateRow>
    @Query("DELETE FROM observations WHERE sessionId = :sessionId") suspend fun deleteForSession(sessionId: Long): Int
}

data class FollowerCandidateRow(val deviceId: String, val hits: Int, val firstMs: Long, val lastMs: Long)

@Dao
interface AlertDao {
    @Insert suspend fun insert(alert: AlertEntity): Long
    @Query("SELECT * FROM alerts ORDER BY timestampMs DESC LIMIT :limit") fun recent(limit: Int = 200): Flow<List<AlertEntity>>
    @Query("UPDATE alerts SET acknowledged = 1 WHERE rowId = :id") suspend fun acknowledge(id: Long)
    @Query("SELECT * FROM alerts ORDER BY timestampMs DESC LIMIT :limit") suspend fun list(limit: Int): List<AlertEntity>
    @Query("DELETE FROM alerts WHERE timestampMs < :cutoff") suspend fun purgeOlderThan(cutoff: Long): Int
}

@Dao
interface EventDao {
    @Insert suspend fun insert(event: EventEntity): Long
    @Query("SELECT * FROM events ORDER BY timestampMs DESC LIMIT :limit") fun recent(limit: Int = 500): Flow<List<EventEntity>>
    @Query("SELECT * FROM events WHERE (:kind IS NULL OR kind = :kind) ORDER BY timestampMs DESC") suspend fun filtered(kind: String?): List<EventEntity>
    @Query("DELETE FROM events WHERE timestampMs < :cutoff") suspend fun purgeOlderThan(cutoff: Long): Int
}

@Dao
interface GeofenceDao {
    @Upsert suspend fun upsert(fence: GeofenceEntity)
    @Query("SELECT * FROM geofences") fun observeAll(): Flow<List<GeofenceEntity>>
    @Query("SELECT * FROM geofences") suspend fun all(): List<GeofenceEntity>
    @Query("DELETE FROM geofences WHERE id = :id") suspend fun delete(id: String)
}

@Dao
interface BaselineDao {
    @Upsert suspend fun upsert(row: BaselineEntity)
    @Query("SELECT * FROM baselines WHERE geofenceId = :geofenceId") suspend fun forGeofence(geofenceId: String): List<BaselineEntity>
}

@Dao
interface SessionDao {
    @Insert suspend fun start(session: SessionEntity): Long
    @Query("UPDATE sessions SET endMs = :endMs WHERE id = :id") suspend fun finish(id: Long, endMs: Long)
    @Query("SELECT * FROM sessions WHERE endMs IS NULL ORDER BY startMs DESC LIMIT 1") suspend fun active(): SessionEntity?
    @Query("DELETE FROM sessions WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM sessions ORDER BY startMs DESC LIMIT :limit") suspend fun recent(limit: Int): List<SessionEntity>
}
