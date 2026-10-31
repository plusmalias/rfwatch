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
    @Query("SELECT * FROM devices ORDER BY lastSeenMs DESC") fun observeAll(): Flow<List<DeviceEntity>>
    @Query("SELECT * FROM devices WHERE id = :id") suspend fun byId(id: String): DeviceEntity?
    @Query("UPDATE devices SET isAllowlisted = :allow WHERE id = :id") suspend fun setAllowlisted(id: String, allow: Boolean)
    @Query("UPDATE devices SET isOwnDevice = :own, ownDeviceConfirmed = :confirmed WHERE id = :id")
    suspend fun setOwnDevice(id: String, own: Boolean, confirmed: Boolean)
    @Query("SELECT id FROM devices WHERE isAllowlisted = 1") suspend fun allowlistedIds(): List<String>
    @Query("SELECT id FROM devices WHERE isOwnDevice = 1") suspend fun ownDeviceIds(): List<String>
}

@Dao
interface ObservationDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insert(obs: ObservationEntity): Long
    @Query("SELECT * FROM observations WHERE deviceId = :id ORDER BY timestampMs") suspend fun forDevice(id: String): List<ObservationEntity>
    @Query("SELECT * FROM observations WHERE deviceId = :id AND lat IS NOT NULL ORDER BY timestampMs") suspend fun geoForDevice(id: String): List<ObservationEntity>
    @Query("DELETE FROM observations WHERE timestampMs < :cutoff") suspend fun purgeOlderThan(cutoff: Long): Int
}

@Dao
interface AlertDao {
    @Insert suspend fun insert(alert: AlertEntity): Long
    @Query("SELECT * FROM alerts ORDER BY timestampMs DESC LIMIT :limit") fun recent(limit: Int = 200): Flow<List<AlertEntity>>
    @Query("UPDATE alerts SET acknowledged = 1 WHERE rowId = :id") suspend fun acknowledge(id: Long)
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
    @Query("SELECT * FROM sessions ORDER BY startMs DESC LIMIT :limit") suspend fun recent(limit: Int): List<SessionEntity>
}
