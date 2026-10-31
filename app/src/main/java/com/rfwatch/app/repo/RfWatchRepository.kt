package com.rfwatch.app.repo

import com.rfwatch.app.alerts.AlertManager
import com.rfwatch.app.data.DeviceEntity
import com.rfwatch.app.data.ObservationEntity
import com.rfwatch.app.data.RfWatchDatabase
import com.rfwatch.app.scan.BleScanner
import com.rfwatch.app.scan.CellScanner
import com.rfwatch.app.scan.LocationFixHolder
import com.rfwatch.app.scan.RttRanger
import com.rfwatch.app.scan.WifiScanner
import com.rfwatch.core.cs.cell.CellAssessment
import com.rfwatch.core.cs.cell.CellThreatLevel
import com.rfwatch.core.cs.cell.StingrayDetector
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.rfwatch.app.sensors.HeadingProvider
import com.rfwatch.app.sensors.LocationProvider
import com.rfwatch.core.alerts.AlertType
import com.rfwatch.core.cs.TrackerMatch
import com.rfwatch.core.cs.TrackerParser
import com.rfwatch.core.cs.TrackerAlertGate
import com.rfwatch.core.cs.TrackerType
import com.rfwatch.core.cs.FollowerExclusion
import com.rfwatch.core.cs.FollowerScore
import com.rfwatch.core.cs.FollowerScorer
import com.rfwatch.core.cs.Sighting
import com.rfwatch.core.cs.UserPoint
import com.rfwatch.core.df.DriveByEstimator
import com.rfwatch.core.df.EmitterEstimate
import com.rfwatch.core.df.EstimateStatus
import com.rfwatch.core.df.SweepFit
import com.rfwatch.core.df.SweepResult
import com.rfwatch.core.geo.angularDiffDeg
import com.rfwatch.core.geo.haversineM
import com.rfwatch.core.model.Band
import com.rfwatch.core.model.GpsFix
import com.rfwatch.core.model.Heading
import com.rfwatch.core.model.HeadingSource
import com.rfwatch.core.model.RadioObservation
import com.rfwatch.core.model.SensorAccuracy
import com.rfwatch.core.rules.DeviceFacts
import com.rfwatch.core.rules.RuleEngine
import com.rfwatch.core.rules.Threat
import androidx.room.withTransaction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.launch

/** Live progress of a running sweep: how many usable samples the target has produced. */
data class SweepLive(val samples: Int, val stepSamples: Int, val lastRssi: Int?, val headingAvailable: Boolean)

/** One raw RSSI reading from the hunt target (every advert, unthrottled). */
data class HuntSample(val rssi: Int, val timestampMs: Long)

/** User-controlled scan capture; observations are tagged only while this session is active. */
data class ScanSessionStatus(
    val id: Long? = null,
    val startedAtMs: Long? = null,
    val observationCount: Int = 0,
) {
    val isActive: Boolean get() = id != null
}

/** A live contact shown on the radar. Bearing/estimate are null until a DF method runs. */
data class LiveContact(
    val id: String,
    val band: Band,
    val label: String,
    val classification: String,
    val trackerType: TrackerType? = null,
    val lastRssi: Int = -100,
    val estimate: EmitterEstimate? = null,
    val lastSeenMs: Long = 0,
    val brand: String? = null,
    val category: String? = null,
    val kind: com.rfwatch.core.id.DeviceKind = com.rfwatch.core.id.DeviceKind.UNKNOWN,
)

/** A drone heard via Remote ID / DJI DroneID, with everything it has broadcast so far. */
data class DroneTrack(
    val id: String,
    val info: com.rfwatch.core.id.RemoteIdInfo,
    val rssi: Int,
    val lastSeenMs: Long,
    val source: String,
)

/** Home-watch state for Settings and the Radar banner. */
data class HomeStatus(
    val isSet: Boolean = false,
    val visitsLearned: Int = 0,
    val inside: Boolean = false,
    val newDevices: List<String> = emptyList(),
) {
    val mature: Boolean get() = visitsLearned >= com.rfwatch.core.cs.Baseline.MIN_SESSIONS
}

/** One GPS-tagged hearing of a device, for the per-device locate chart. */
data class DeviceSighting(val lat: Double, val lon: Double, val rssi: Int, val timestampMs: Long, val accuracyM: Double)

/** Everything the locate chart needs for one device. */
data class LocateData(
    val sightings: List<DeviceSighting>,
    val centroid: com.rfwatch.core.df.CentroidEstimate?,
    val driveBy: EmitterEstimate?,
)

/**
 * Central integration hub. Consumes the live BLE / Wi-Fi / location / heading streams,
 * persists observations, classifies each device with the unit-tested :core parsers and
 * rule engine, keeps the live radar contact set, and raises alerts. Direction-finding
 * (sweep, drive-by) and follower/room-sweep analyses are driven on demand by the UI and all
 * delegate their maths to :core.
 */
class RfWatchRepository(
    private val database: RfWatchDatabase,
    private val alertManager: AlertManager,
    private val ruleEngine: RuleEngine,
    private val bleScanner: BleScanner,
    private val wifiScanner: WifiScanner,
    private val rttRanger: RttRanger,
    private val cellScanner: CellScanner,
    private val headingProvider: HeadingProvider,
    private val locationProvider: LocationProvider,
    /** Receives the widget / tile status summary (see [publishStatus]). */
    private val onStatus: (com.rfwatch.app.widget.StatusSummary) -> Unit = {},
    /** User's retention window in days (Settings); read each time the purge runs. */
    private val retentionDays: () -> Int = { com.rfwatch.core.data.RetentionPolicy.DEFAULT_RETENTION_DAYS },
) {
    // All scan ingestion runs on one background thread: the caches below are plain maps
    // touched only from this dispatcher, and the main thread never parses adverts.
    @OptIn(ExperimentalCoroutinesApi::class)
    private val ingestDispatcher = Dispatchers.Default.limitedParallelism(1)
    @Volatile private var monitorJob: Job? = null

    // Write-behind device cache: adverts arrive several times a second per device, so rows are
    // updated in memory and flushed in one transaction every FLUSH_INTERVAL_MS.
    private val deviceCache = HashMap<String, DeviceEntity>()
    private var deviceCacheLoaded = false
    private val dirtyDevices = LinkedHashMap<String, DeviceEntity>()
    private val pendingObservations = ArrayList<ObservationEntity>()
    private val lastObservationMs = HashMap<String, Long>()
    private var activeSessionId: Long? = null
    // Radar contacts are rebuilt here and published at most every CONTACT_PUBLISH_INTERVAL_MS.
    private val liveContacts = HashMap<String, LiveContact>()
    private var contactsDirty = false

    private val writeMutex = Mutex()

    private val lawTracker = com.rfwatch.core.alerts.LawEncounterTracker()
    private val _lawEncounter = MutableStateFlow<com.rfwatch.core.alerts.LawEncounter?>(null)
    /** Current / most recent possible law-enforcement encounter, for the live banner. */
    val lawEncounter: StateFlow<com.rfwatch.core.alerts.LawEncounter?> = _lawEncounter.asStateFlow()

    private val stingray = StingrayDetector()
    private val trackerAlertGate = TrackerAlertGate()
    private val fixHolder = LocationFixHolder()

    private val _heading = MutableStateFlow(Heading(null, HeadingSource.NONE, SensorAccuracy.UNRELIABLE))
    val heading: StateFlow<Heading> = _heading.asStateFlow()

    private val _userLocation = MutableStateFlow<GpsFix?>(null)
    val userLocation: StateFlow<GpsFix?> = _userLocation.asStateFlow()

    private val _contacts = MutableStateFlow<Map<String, LiveContact>>(emptyMap())
    val contacts: StateFlow<Map<String, LiveContact>> = _contacts.asStateFlow()

    private val _sweep = MutableStateFlow<SweepResult?>(null)
    val sweep: StateFlow<SweepResult?> = _sweep.asStateFlow()

    // Live room-sweep candidates, keyed by radio address and finding reason.
    private val roomMap = HashMap<String, com.rfwatch.core.cs.SweepFinding>()
    private val roomLastSeenMs = HashMap<String, Long>()
    private var roomDirty = false
    /** Last parse per BLE address, reused while the advert payload is byte-identical. */
    private class BleParse(val key: String, val match: TrackerMatch?, val fingerprint: com.rfwatch.core.id.Fingerprint)
    private val bleParseCache = HashMap<String, BleParse>()
    private val _room = MutableStateFlow<List<com.rfwatch.core.cs.SweepFinding>>(emptyList())
    val roomFindings: StateFlow<List<com.rfwatch.core.cs.SweepFinding>> = _room.asStateFlow()

    private val _cell = MutableStateFlow<CellAssessment?>(null)
    val cellAssessment: StateFlow<CellAssessment?> = _cell.asStateFlow()

    @Volatile private var sweepTargetId: String? = null
    // Guided sweeps (no compass) supply the heading: degrees clockwise from the start direction.
    @Volatile private var guidedHeadingDeg: Double? = null
    private val sweepLock = Any()
    private val sweepHeadings = ArrayList<Double>()
    private val sweepRssi = ArrayList<Double>()
    private var sweepStepSamples = 0
    private val _sweepLive = MutableStateFlow<SweepLive?>(null)
    val sweepLive: StateFlow<SweepLive?> = _sweepLive.asStateFlow()

    @Volatile private var huntTargetId: String? = null
    private val _hunt = MutableStateFlow<HuntSample?>(null)
    val hunt: StateFlow<HuntSample?> = _hunt.asStateFlow()

    private val _scanSession = MutableStateFlow(ScanSessionStatus())
    val scanSession: StateFlow<ScanSessionStatus> = _scanSession.asStateFlow()

    /** Hunt (hot/cold) mode: stream every RSSI reading from [deviceId]; null stops. */
    fun setHuntTarget(deviceId: String?) {
        huntTargetId = deviceId
        _hunt.value = null
    }

    /** Whether this phone can give a compass heading (needed for the continuous sweep). */
    val hasCompass: Boolean get() = headingProvider.hasCompass

    /**
     * Device inventory in discovery order (firstSeenMs DESC, id ASC), served from the in-memory
     * write-behind cache and republished after each flush. The cache is the only writer of the
     * devices table, so this matches the database without re-reading (and decrypting) the whole
     * table every few seconds. Seeded on first collection even when monitoring is stopped.
     */
    private val _devices = MutableStateFlow<List<DeviceEntity>?>(null)
    val deviceFlow: kotlinx.coroutines.flow.Flow<List<DeviceEntity>> = kotlinx.coroutines.flow.flow {
        if (_devices.value == null) withContext(ingestDispatcher) { ensureDeviceCache(); publishDevices() }
        emitAll(_devices.filterNotNull())
    }

    /** Snapshot the cache for the UI. Call on [ingestDispatcher] only (the cache is a plain map). */
    private fun publishDevices() {
        _devices.value = deviceCache.values.sortedWith(DISCOVERY_ORDER)
    }
    val alertFlow get() = database.alertDao().recent()
    val eventFlow get() = database.eventDao().recent()

    suspend fun recentObservations(deviceId: String, limit: Int = 60) =
        database.observationDao().recentForDevice(deviceId, limit.coerceIn(1, 60))

    /** Start a named-by-time capture. Repeated taps return the existing active session. */
    suspend fun startScanSession(): Long = withContext(ingestDispatcher) {
        loadActiveSession()
        activeSessionId?.let { return@withContext it }
        val now = System.currentTimeMillis()
        val id = database.sessionDao().start(com.rfwatch.app.data.SessionEntity(startMs = now))
        activeSessionId = id
        _scanSession.value = ScanSessionStatus(id = id, startedAtMs = now, observationCount = 0)
        id
    }

    /** Finish and preserve the tagged observations as a completed session. */
    suspend fun saveScanSession(): Boolean = withContext(ingestDispatcher) {
        loadActiveSession()
        val id = activeSessionId ?: return@withContext false
        flushPending()
        val endedAt = System.currentTimeMillis()
        database.withTransaction {
            database.sessionDao().finish(id, endedAt)
            database.eventDao().insert(
                com.rfwatch.app.data.EventEntity(
                    kind = "SESSION_SAVED", timestampMs = endedAt,
                    detail = "Scan session saved · ${_scanSession.value.observationCount} observations",
                )
            )
        }
        activeSessionId = null
        _scanSession.value = ScanSessionStatus()
        true
    }

    /** Discard only the current session's tagged observations; global device inventory remains. */
    suspend fun clearScanSession(): Boolean = withContext(ingestDispatcher) {
        loadActiveSession()
        val id = activeSessionId ?: return@withContext false
        flushPending()
        database.withTransaction {
            database.observationDao().deleteForSession(id)
            database.sessionDao().delete(id)
        }
        activeSessionId = null
        _scanSession.value = ScanSessionStatus()
        true
    }

    /** Analyze co-movement on demand; no notification is raised from a tracker signature alone. */
    suspend fun analyzeFollower(deviceId: String): FollowerScore = withContext(ingestDispatcher) {
        ensureDeviceCache()
        flushPending()
        val device = deviceCache[deviceId] ?: database.deviceDao().byId(deviceId)
        val observations = database.observationDao().recentGeoForDevice(deviceId, FOLLOWER_DEVICE_HISTORY_LIMIT)
            .asReversed()
            .filter { it.lat != null && it.lon != null && (it.gpsAccuracyM ?: 100.0) <= MAX_FIX_ACCURACY_M }
        val sightings = observations.map {
            Sighting(it.lat!!, it.lon!!, it.timestampMs, it.rssi)
        }
        val timeStart = sightings.firstOrNull()?.timestampMs ?: 0L
        val timeEnd = sightings.lastOrNull()?.timestampMs ?: 0L
        val rawTrack = if (sightings.isEmpty()) emptyList() else database.observationDao()
            .recentGeoTrack(FOLLOWER_USER_TRACK_LIMIT)
            .asReversed()
            .filter { it.timestampMs in timeStart..timeEnd && (it.gpsAccuracyM ?: 100.0) <= MAX_FIX_ACCURACY_M }
            .mapNotNull { row ->
                val lat = row.lat ?: return@mapNotNull null
                val lon = row.lon ?: return@mapNotNull null
                UserPoint(lat, lon, row.timestampMs)
            }
        val userTrack = reduceUserTrack(rawTrack)
        val exclusion = when {
            device?.isAllowlisted == true -> FollowerExclusion.ALLOWLISTED
            device?.isOwnDevice == true -> FollowerExclusion.OWN_DEVICE
            device?.mobility == "STATIONARY" -> FollowerExclusion.STATIONARY_INFRASTRUCTURE
            else -> FollowerExclusion.NONE
        }
        FollowerScorer.score(deviceId, sightings, userTrack, exclusion)
    }

    // ---- Home watch: learn the devices normally around home, flag concerning newcomers ----
    private var homeFence: com.rfwatch.app.data.GeofenceEntity? = null
    private var homeBaseline = HashMap<String, Int>()
    private var homeVisits = 0
    private var insideHome = false
    private var visitStartMs = 0L
    private val visitDevices = HashMap<String, Pair<Long, Int>>() // id -> (first heard ms, hits)
    private val lastHomeAlertMs = HashMap<String, Long>()
    private val _home = MutableStateFlow(HomeStatus())
    val homeStatus: StateFlow<HomeStatus> = _home.asStateFlow()

    private suspend fun loadHome() {
        homeFence = database.geofenceDao().all().firstOrNull { it.id == HOME_ID }
        val rows = database.baselineDao().forGeofence(HOME_ID)
        homeVisits = rows.firstOrNull { it.deviceId == VISITS_KEY }?.sessionsPresent ?: 0
        homeBaseline = HashMap(rows.filter { it.deviceId != VISITS_KEY }.associate { it.deviceId to it.sessionsPresent })
        publishHome()
    }

    private fun publishHome(newDevice: String? = null) {
        _home.value = HomeStatus(
            isSet = homeFence != null, visitsLearned = homeVisits, inside = insideHome,
            newDevices = (if (newDevice != null) listOf(newDevice) else emptyList()) + _home.value.newDevices.take(9),
        )
    }

    /** Set Home at the current GPS fix. Returns false without a usable fix. */
    suspend fun setHomeHere(): Boolean = withContext(ingestDispatcher) {
        val fix = fixHolder.current ?: return@withContext false
        if (fix.accuracyM > MAX_FIX_ACCURACY_M) return@withContext false
        val fence = com.rfwatch.app.data.GeofenceEntity(HOME_ID, "Home", fix.lat, fix.lon, HOME_RADIUS_M)
        database.geofenceDao().upsert(fence)
        homeFence = fence; insideHome = true; visitStartMs = System.currentTimeMillis(); visitDevices.clear()
        publishHome()
        true
    }

    suspend fun clearHome() = withContext(ingestDispatcher) {
        database.geofenceDao().delete(HOME_ID)
        homeFence = null; homeBaseline.clear(); homeVisits = 0; insideHome = false; visitDevices.clear()
        _home.value = HomeStatus()
    }

    private suspend fun onHomeFix(fix: GpsFix) {
        val fence = homeFence ?: return
        val inside = haversineM(fix.lat, fix.lon, fence.lat, fence.lon) <= fence.radiusM + fix.accuracyM.coerceAtMost(50.0)
        val now = System.currentTimeMillis()
        if (inside && !insideHome) { visitStartMs = now; visitDevices.clear() }
        if ((!inside && insideHome) || (inside && now - visitStartMs >= HOME_VISIT_ROLL_MS)) closeHomeVisit(now)
        if (inside && visitStartMs == 0L) visitStartMs = now
        if (inside != insideHome) { insideHome = inside; publishHome() }
    }

    /** A home visit of ≥ [HOME_MIN_VISIT_MS] becomes one baseline session. */
    private suspend fun closeHomeVisit(now: Long) {
        if (now - visitStartMs >= HOME_MIN_VISIT_MS) {
            homeVisits++
            database.baselineDao().upsert(com.rfwatch.app.data.BaselineEntity(HOME_ID, VISITS_KEY, homeVisits))
            visitDevices.filterValues { it.second >= HOME_BASELINE_MIN_HITS }.keys.forEach { id ->
                val n = (homeBaseline[id] ?: 0) + 1
                homeBaseline[id] = n
                database.baselineDao().upsert(com.rfwatch.app.data.BaselineEntity(HOME_ID, id, n))
            }
            publishHome()
        }
        visitStartMs = now
        visitDevices.clear()
    }

    private suspend fun onHomeDevice(id: String, device: DeviceEntity, rssi: Int, ts: Long) {
        if (!insideHome || homeFence == null) return
        val (first, hits) = visitDevices[id] ?: (ts to 0)
        visitDevices[id] = first to hits + 1
        if (homeVisits < com.rfwatch.core.cs.Baseline.MIN_SESSIONS) return
        if (hits + 1 < HOME_ALERT_MIN_HITS || ts - first < HOME_ALERT_MIN_SPAN_MS || rssi < HOME_ALERT_MIN_RSSI) return
        if (device.isOwnDevice || device.isAllowlisted) return
        val known = (homeBaseline[id] ?: 0).toDouble() / homeVisits >= 0.2
        if (known) return
        val kind = runCatching { com.rfwatch.core.id.DeviceKind.valueOf(device.kind ?: "") }.getOrNull()
        val stableWifi = device.band == Band.WIFI.name &&
            id.substringBefore(':').toIntOrNull(16)?.let { !com.rfwatch.core.id.VendorDb.isLocallyAdministered(it) } == true
        val concerning = kind in HOME_CONCERNING_KINDS || stableWifi || device.trackerType != null
        if (!concerning) return
        if (ts - (lastHomeAlertMs[id] ?: 0L) < HOME_ALERT_COOLDOWN_MS) return
        lastHomeAlertMs[id] = ts
        val what = listOfNotNull(device.displayName, device.brand, device.model ?: device.category).distinct().joinToString(" · ").ifBlank { id }
        val detail = "$what — new at home, heard ${hits + 1}× over ${(ts - first) / 60_000} min (${rssi} dBm). " +
            "Not seen in ${homeVisits} earlier home visits."
        alertManager.raise(AlertType.NEW_AT_HOME, "New device at home", detail, null, 0.0, deviceId = id)
        database.alertDao().insert(com.rfwatch.app.data.AlertEntity(type = AlertType.NEW_AT_HOME.name, timestampMs = ts, deviceId = id, message = detail))
        database.eventDao().insert(com.rfwatch.app.data.EventEntity(kind = "NEW_AT_HOME", timestampMs = ts, deviceId = id, detail = detail, lat = homeFence?.lat, lon = homeFence?.lon))
        publishHome(what)
    }

    private val _followers = MutableStateFlow<Map<String, FollowerScore>>(emptyMap())
    /** Devices currently judged to be travelling with the user (background check). */
    val followers: StateFlow<Map<String, FollowerScore>> = _followers.asStateFlow()
    private val lastFollowerAlertMs = HashMap<String, Long>()

    /**
     * Background follower check (AirGuard-style): only when the user has actually travelled,
     * score devices heard at several GPS-tagged places over the window with [FollowerScorer]
     * and alert on new followers. Own / trusted / stationary devices are excluded by the scorer.
     */
    private suspend fun checkFollowers(nowMs: Long) {
        val since = nowMs - FOLLOWER_WINDOW_MS
        val track = database.observationDao().recentGeoTrack(FOLLOWER_USER_TRACK_LIMIT)
            .filter { it.timestampMs >= since && it.lat != null && it.lon != null && (it.gpsAccuracyM ?: 100.0) <= MAX_FIX_ACCURACY_M }
            .asReversed()
            .map { UserPoint(it.lat!!, it.lon!!, it.timestampMs) }
        val reduced = reduceUserTrack(track)
        val travelled = reduced.zipWithNext().sumOf { (a, b) -> haversineM(a.lat, a.lon, b.lat, b.lon) }
        if (travelled < FollowerScorer.MIN_USER_TRAVEL_M) { _followers.value = emptyMap(); return }
        val candidates = database.observationDao().followerCandidates(since, FOLLOWER_MIN_HITS, FollowerScorer.MIN_SPAN_MS, FOLLOWER_CANDIDATE_LIMIT)
        val found = HashMap<String, FollowerScore>()
        for (row in candidates) {
            val score = analyzeFollower(row.deviceId)
            if (!score.isFollower) continue
            found[row.deviceId] = score
            if (nowMs - (lastFollowerAlertMs[row.deviceId] ?: 0L) < FOLLOWER_ALERT_COOLDOWN_MS) continue
            lastFollowerAlertMs[row.deviceId] = nowMs
            val device = deviceCache[row.deviceId]
            val law = device?.kind == com.rfwatch.core.id.DeviceKind.LAW_ENFORCEMENT.name
            val type = if (law) AlertType.LAW_VEHICLE_FOLLOWING else AlertType.POSSIBLE_FOLLOWER
            val what = listOfNotNull(device?.displayName, device?.brand, device?.model ?: device?.category).distinct().joinToString(" · ").ifBlank { row.deviceId }
            val detail = "$what — heard at ${score.distinctLocations} places ${"%.1f".format(score.maxSeparationM / 1000)} km apart over " +
                "${score.spanMs / 60_000} min while you travelled ${"%.1f".format(score.userTravelM / 1000)} km. If it's yours, mark it Mine."
            alertManager.raise(type, titleFor(type), detail, null, score.score, deviceId = row.deviceId)
            database.alertDao().insert(com.rfwatch.app.data.AlertEntity(type = type.name, timestampMs = nowMs, deviceId = row.deviceId, message = detail, confidence = score.score))
            val last = score.timeline.lastOrNull()
            database.eventDao().insert(com.rfwatch.app.data.EventEntity(kind = "FOLLOWER", timestampMs = nowMs, deviceId = row.deviceId, detail = detail, lat = last?.lat, lon = last?.lon))
        }
        _followers.value = found
    }

    private val _drones = MutableStateFlow<Map<String, DroneTrack>>(emptyMap())
    /** Drones heard recently (Remote ID position / pilot position when broadcast). */
    val drones: StateFlow<Map<String, DroneTrack>> = _drones.asStateFlow()
    private val lastDroneAlertMs = HashMap<String, Long>()

    private suspend fun onDrone(id: String, info: com.rfwatch.core.id.RemoteIdInfo, rssi: Int, ts: Long, source: String) {
        val key = info.serial ?: id
        val merged = (_drones.value[key]?.info?.merge(info)) ?: info
        _drones.value = (_drones.value + (key to DroneTrack(key, merged, rssi, ts, source)))
            .filterValues { ts - it.lastSeenMs < DRONE_TTL_MS }
        if (ts - (lastDroneAlertMs[key] ?: 0L) < DRONE_ALERT_COOLDOWN_MS) return
        lastDroneAlertMs[key] = ts
        val me = fixHolder.current
        fun distTo(lat: Double?, lon: Double?) = if (me == null || lat == null || lon == null) null
            else com.rfwatch.core.geo.haversineM(me.lat, me.lon, lat, lon)
        val droneDist = distTo(merged.lat, merged.lon)
        val pilotDist = distTo(merged.operatorLat, merged.operatorLon)
        val opLat = merged.operatorLat
        val opLon = merged.operatorLon
        val pilotDir = if (me != null && opLat != null && opLon != null) {
            val e = com.rfwatch.core.geo.LocalFrame(me.lat, me.lon).toEnu(opLat, opLon)
            listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[((com.rfwatch.core.geo.bearingDeg(e.east, e.north) / 45.0 + 0.5).toInt()) % 8]
        } else null
        val detail = listOfNotNull(
            merged.serial?.let { "ID $it" } ?: "no Remote ID ($source)",
            droneDist?.let { "drone ~${it.toInt()} m away" },
            merged.heightAglM?.let { "${it.toInt()} m up" } ?: merged.altitudeM?.let { "alt ${it.toInt()} m" },
            pilotDist?.let { "pilot ~${it.toInt()} m ${pilotDir ?: ""}".trim() },
            "$rssi dBm",
        ).joinToString(" · ")
        alertManager.raise(AlertType.DRONE_NEARBY, "Drone nearby", detail, null, 0.0)
        database.alertDao().insert(
            com.rfwatch.app.data.AlertEntity(type = AlertType.DRONE_NEARBY.name, timestampMs = ts, deviceId = id, message = detail),
        )
        database.eventDao().insert(
            com.rfwatch.app.data.EventEntity(kind = "DRONE", timestampMs = ts, deviceId = id, detail = detail, lat = merged.lat ?: me?.lat, lon = merged.lon ?: me?.lon),
        )
    }

    private val _positions = MutableStateFlow<Map<String, com.rfwatch.core.df.CentroidEstimate>>(emptyMap())
    /** RSSI-weighted position estimates for devices on the Radar (refreshed while it is visible). */
    val positions: StateFlow<Map<String, com.rfwatch.core.df.CentroidEstimate>> = _positions.asStateFlow()

    /** Recompute RSSI-centroid positions for [ids] from their recent GPS-tagged observations. */
    suspend fun refreshPositions(ids: Collection<String>) {
        val next = HashMap<String, com.rfwatch.core.df.CentroidEstimate>()
        for (id in ids.take(POSITION_DEVICE_LIMIT)) {
            val obs = database.observationDao().recentGeoForDevice(id, POSITION_SAMPLE_LIMIT).map { it.toRadioObservation() }
            com.rfwatch.core.df.RssiCentroidLocator.estimate(obs)?.let { next[id] = it }
        }
        _positions.value = next
    }

    /** Logs a finished skimmer check (all results, verbose) and alerts on HIGH findings. */
    suspend fun logSkimmerCheck(results: List<com.rfwatch.core.cs.SkimmerAssessment>, devicesSeen: Int) {
        val now = System.currentTimeMillis()
        val fix = fixHolder.current
        val high = results.filter { it.level == com.rfwatch.core.cs.SkimmerLevel.HIGH }
        val summary = "Skimmer check: $devicesSeen Classic Bluetooth devices, ${high.size} high, " +
            "${results.count { it.level == com.rfwatch.core.cs.SkimmerLevel.MEDIUM }} medium. " +
            results.filter { it.level != com.rfwatch.core.cs.SkimmerLevel.LOW }.joinToString(" | ") {
                "${it.level} ${it.score}/100 ${it.address} \"${it.name ?: "(no name)"}\": ${it.reasons.joinToString("; ")}"
            }
        database.eventDao().insert(com.rfwatch.app.data.EventEntity(kind = "SKIMMER_CHECK", timestampMs = now, detail = summary, lat = fix?.lat, lon = fix?.lon))
        high.forEach { a ->
            val detail = "${a.address} \"${a.name ?: "(no name)"}\" scored ${a.score}/100 — ${a.reasons.joinToString("; ")}"
            alertManager.raise(AlertType.SKIMMER_SUSPECTED, titleFor(AlertType.SKIMMER_SUSPECTED), detail, null, a.score / 100.0)
            database.alertDao().insert(com.rfwatch.app.data.AlertEntity(type = AlertType.SKIMMER_SUSPECTED.name, timestampMs = now, deviceId = a.address, message = detail))
        }
    }

    /** Logs a stalkerware scan (verbose, local only) and alerts on CRITICAL/HIGH apps — no speech, discreet. */
    suspend fun logStalkerwareScan(results: List<com.rfwatch.core.cs.AppAssessment>) {
        val now = System.currentTimeMillis()
        val flagged = results.filter { it.risk == com.rfwatch.core.cs.AppRisk.CRITICAL || it.risk == com.rfwatch.core.cs.AppRisk.HIGH }
        val summary = "Stalkerware check: ${results.size} apps, ${flagged.size} critical/high. " + flagged.joinToString(" | ") {
            "${it.risk} ${it.score} ${it.facts.label} (${it.facts.packageName})${it.family?.let { f -> " = ${f.name}" } ?: ""}: ${it.reasons.joinToString("; ")}"
        }
        database.eventDao().insert(com.rfwatch.app.data.EventEntity(kind = "STALKERWARE_SCAN", timestampMs = now, detail = summary))
        if (flagged.isNotEmpty()) {
            val detail = flagged.joinToString { "${it.facts.label} (${it.risk})" }
            alertManager.raise(AlertType.STALKERWARE, titleFor(AlertType.STALKERWARE), detail, null, 1.0, speak = false)
            database.alertDao().insert(com.rfwatch.app.data.AlertEntity(type = AlertType.STALKERWARE.name, timestampMs = now, deviceId = null, message = summary))
        }
    }

    /** Logs a car sweep's findings (with location) to the event log. */
    suspend fun logCarSweep(lines: List<String>, heard: Int) {
        val fix = fixHolder.current
        database.eventDao().insert(
            com.rfwatch.app.data.EventEntity(
                kind = "CAR_SWEEP", timestampMs = System.currentTimeMillis(),
                detail = "Car sweep: $heard signals heard, ${lines.size} to check. " + lines.joinToString(" | "),
                lat = fix?.lat, lon = fix?.lon,
            )
        )
    }

    /** Evidence report share intent (all alerts, or one device's full history). */
    suspend fun evidenceIntent(context: android.content.Context, deviceId: String?): android.content.Intent {
        withContext(ingestDispatcher) { flushPending() }
        return com.rfwatch.app.export.EvidenceExporter.export(context, database, deviceId)
    }

    /** Sightings + both position estimates for one device's locate chart. */
    suspend fun locateData(deviceId: String): LocateData {
        val rows = database.observationDao().recentGeoForDevice(deviceId, LOCATE_SAMPLE_LIMIT)
        val obs = rows.map { it.toRadioObservation() }
        return LocateData(
            sightings = rows.mapNotNull { r ->
                if (r.lat == null || r.lon == null) null else DeviceSighting(r.lat, r.lon, r.rssi, r.timestampMs, r.gpsAccuracyM ?: 20.0)
            },
            centroid = com.rfwatch.core.df.RssiCentroidLocator.estimate(obs),
            driveBy = _contacts.value[deviceId]?.estimate,
        )
    }

    /** Refresh a bounded set of live motion/location fits while the online map is visible. */
    suspend fun refreshMapEstimates() {
        val candidates = database.deviceDao().recentMapped(MAP_ESTIMATE_DEVICE_LIMIT)
        for (device in candidates) {
            val observations = database.observationDao()
                .recentGeoForDevice(device.id, MAP_ESTIMATE_SAMPLE_LIMIT)
                .map { it.toRadioObservation() }
            if (observations.count { it.fix != null } < com.rfwatch.core.df.DriveByEstimator.MIN_SAMPLES) continue
            val previous = _contacts.value[device.id] ?: continue
            val estimate = DriveByEstimator.estimate(
                observations, fixHolder.current, System.currentTimeMillis(), previous.estimate,
            )
            _contacts.update { current ->
                val contact = current[device.id] ?: return@update current
                current + (device.id to contact.copy(estimate = estimate))
            }
        }
    }

    /**
     * Starts the scan collectors on [scope]. Repeated calls (every service start request)
     * are ignored while the previous collectors are still running; once that scope is
     * cancelled a later call starts afresh.
     */
    fun startMonitoring(scope: CoroutineScope) {
        if (monitorJob?.isActive == true) return
        monitorJob = scope.launch(ingestDispatcher) { runMonitoring(this) }
    }

    private suspend fun runMonitoring(scope: CoroutineScope) {
        ensureDeviceCache()
        // Seed the evil-twin detector with every Wi-Fi network already in the inventory.
        deviceCache.values.filter { it.band == Band.WIFI.name && !it.displayName.isNullOrBlank() }.forEach {
            evilTwin.learn(it.displayName!!, it.id, it.wifiSecurity ?: "Unknown", it.firstSeenMs)
        }
        runCatching { loadHome() }
        loadActiveSession()
        scope.launch {
            try {
                while (isActive) {
                    delay(FLUSH_INTERVAL_MS)
                    flushPending()
                }
            } finally {
                withContext(NonCancellable) { flushPending() }
            }
        }
        scope.launch {
            while (isActive) {
                delay(CONTACT_PUBLISH_INTERVAL_MS)
                publishContacts()
                publishRoom(System.currentTimeMillis())
            }
        }
        scope.launch {
            delay(RETENTION_FIRST_RUN_MS)
            while (isActive) {
                runCatching { purgeExpired() }
                delay(RETENTION_INTERVAL_MS)
            }
        }
        scope.launch {
            while (isActive) {
                delay(FOLLOWER_CHECK_INTERVAL_MS)
                runCatching { checkFollowers(System.currentTimeMillis()) }
            }
        }
        scope.launch {
            while (isActive) {
                delay(LAW_TICK_MS)
                handleLawAction(lawTracker.tick(System.currentTimeMillis()))
                publishStatus()
            }
        }
        scope.launch {
            locationProvider.fixes().collect { fix ->
                fixHolder.update(fix)
                _userLocation.value = fix
                headingProvider.updateFix(fix)
                runCatching { onHomeFix(fix) }
            }
        }
        scope.launch {
            headingProvider.headings().collect { _heading.value = it }
        }
        scope.launch {
            bleScanner.scan().collect { sighting ->
                val adv = sighting.advertisement
                // Most adverts repeat byte-for-byte; reuse the parse/fingerprint/details from the
                // last identical advert of this address and only rebuild when the payload changes.
                val parseKey = "${sighting.rawAdvertisingHex}|${sighting.deviceName}|${sighting.connectable}|${sighting.legacyAdvertisement}"
                val cachedParse = bleParseCache[adv.address]?.takeIf { it.key == parseKey }
                val match = if (cachedParse != null) cachedParse.match else TrackerParser.parse(adv)
                val facts = DeviceFacts(
                    mac = adv.address,
                    ssid = null,
                    name = sighting.deviceName,
                    companyIds = adv.manufacturerData.keys,
                    serviceUuids = adv.serviceUuids16,
                    rssi = adv.rssi,
                )
                com.rfwatch.core.cs.BleSpamDetector.popupType(adv)?.let { type ->
                    bleSpam.onPopupAdvert(adv.address, type, adv.rssi, adv.timestampMs)?.let { onBleSpam(it, adv.timestampMs) }
                }
                adv.serviceData16[com.rfwatch.core.id.RemoteIdParser.BLE_SERVICE_UUID]
                    ?.let { com.rfwatch.core.id.RemoteIdParser.parseBleServiceData(it) }
                    ?.let { onDrone(adv.address, it, adv.rssi, adv.timestampMs, "Bluetooth Remote ID") }
                if (cachedParse != null) {
                    // Unchanged payload: previous radioDetails stay on the row (null = keep).
                    onDevice(adv.address, Band.BLE, sighting.deviceName, adv.rssi, adv.timestampMs, facts, match, null, cachedParse.fingerprint)
                    return@collect
                }
                val addressType = runCatching {
                    val firstOctet = adv.address.substringBefore(':').toInt(16)
                    if ((firstOctet and 0x02) != 0) "Locally administered address (may be private/rotating)" else "Globally administered address"
                }.getOrDefault("Address type unavailable")
                val manufacturerData = adv.manufacturerData.toSortedMap().map { (companyId, bytes) ->
                    "0x${companyId.toString(16).uppercase().padStart(4, '0')}=${com.rfwatch.core.math.Hex.upper(bytes)}"
                }
                val fingerprint = com.rfwatch.core.id.BleFingerprinter.fingerprint(adv, sighting.deviceName)
                bleParseCache[adv.address] = BleParse(parseKey, match, fingerprint)
                val radioDetails = buildList {
                    add("BLE address ${adv.address}")
                    add(addressType)
                    add("Advertised name ${sighting.deviceName ?: "none"}")
                    add("Identification evidence: ${fingerprint.evidence}")
                    add("Manufacturer data ${manufacturerData.ifEmpty { listOf("none") }.joinToString(", ")}")
                    add("16-bit service IDs ${adv.serviceUuids16.sorted().joinToString(", ") { "0x${it.toString(16).uppercase().padStart(4, '0')}" }.ifEmpty { "none" }}")
                    add("All service UUIDs ${sighting.serviceUuids.joinToString(", ").ifEmpty { "none" }}")
                    add("Service-data UUIDs ${sighting.serviceDataUuids.joinToString(", ").ifEmpty { "none" }}")
                    add("${if (sighting.connectable) "Connectable" else "Not connectable"} · ${if (sighting.legacyAdvertisement) "legacy" else "extended"} advertisement · ${sighting.payloadLength} bytes")
                    add("Raw advertisement hex: ${sighting.rawAdvertisingHex}")
                }.joinToString(" · ")
                onDevice(adv.address, Band.BLE, sighting.deviceName, adv.rssi, adv.timestampMs, facts, match, radioDetails, fingerprint)
            }
        }
        scope.launch {
            wifiScanner.scans().collect { list ->
                list.forEach { s ->
                    evilTwin.observe(s.facts.ssid, s.bssid, com.rfwatch.core.id.WifiFingerprinter.security(s.capabilities).label, s.timestampMs)
                        ?.let { onEvilTwin(it, s.bssid, s.facts.rssi, s.timestampMs) }
                    s.pwnagotchi?.let { onAttackTool(s.bssid, "Pwnagotchi \"$it\" (beacon element 222)", "Wi-Fi beacon announces a Pwnagotchi peer", s.facts.rssi, s.timestampMs) }
                    when {
                        s.remoteId != null -> onDrone(s.bssid, s.remoteId, s.facts.rssi, s.timestampMs, "Wi-Fi Remote ID")
                        s.djiDroneId -> onDrone(s.bssid, com.rfwatch.core.id.RemoteIdInfo(description = s.facts.ssid), s.facts.rssi, s.timestampMs, "DJI DroneID beacon")
                    }
                    val fingerprint = if (s.remoteId != null || s.djiDroneId)
                        com.rfwatch.core.id.Fingerprint(com.rfwatch.core.id.DeviceKind.DRONE, "Drone" + (s.remoteId?.serial?.let { " · $it" } ?: ""), "drone beacon vendor element (Remote ID / DJI DroneID)")
                    else com.rfwatch.core.id.WifiFingerprinter.fingerprint(s.facts.ssid, s.bssid)
                    onDevice(
                        s.bssid, Band.WIFI, s.facts.ssid, s.facts.rssi, s.timestampMs, s.facts, null,
                        radioDetails = "${s.radioDetails} · Identification evidence: ${fingerprint.evidence}",
                        fingerprint = fingerprint,
                        wifiSecurity = com.rfwatch.core.id.WifiFingerprinter.security(s.capabilities).label,
                        wifiBand = com.rfwatch.core.id.WifiFingerprinter.band(s.frequencyMhz),
                    )
                }
            }
        }
        // Cellular / IMSI-catcher monitoring: periodic snapshot of serving + neighbour cells.
        scope.launch {
            while (isActive) {
                val now = System.currentTimeMillis()
                val cells = runCatching { cellScanner.scanOnce(now) }.getOrDefault(emptyList())
                if (cells.isNotEmpty()) {
                    val assessment = stingray.analyze(cells, now)
                    _cell.value = assessment
                    maybeCellAlert(assessment, now)
                }
                delay(CELL_SCAN_INTERVAL_MS)
            }
        }
    }

    private var lastCellAlertMs = 0L

    private suspend fun maybeCellAlert(a: CellAssessment, nowMs: Long) {
        if (a.level != CellThreatLevel.HIGH) return
        if (nowMs - lastCellAlertMs < ALERT_DEBOUNCE_MS) return
        lastCellAlertMs = nowMs
        alertManager.raise(AlertType.IMSI_CATCHER, "Possible IMSI catcher", a.servingSummary,
            relativeBearingDeg = null, confidence = a.score)
        database.alertDao().insert(
            com.rfwatch.app.data.AlertEntity(
                type = AlertType.IMSI_CATCHER.name, timestampMs = nowMs, deviceId = null,
                message = a.findings.joinToString("; ") { it.detail }, confidence = a.score,
            )
        )
        database.eventDao().insert(
            com.rfwatch.app.data.EventEntity(
                kind = "IMSI_CATCHER", timestampMs = nowMs,
                detail = "${a.servingSummary} — ${a.findings.joinToString { it.type.name }}",
            )
        )
    }

    private suspend fun onDevice(
        id: String, band: Band, name: String?, rssi: Int, ts: Long,
        facts: DeviceFacts, match: TrackerMatch?, radioDetails: String? = null,
        fingerprint: com.rfwatch.core.id.Fingerprint? = null,
        wifiSecurity: String? = null, wifiBand: String? = null,
    ) {
        val hits = ruleEngine.evaluate(facts)
        val identity = DeviceIdentityClassifier.classify(band, facts, match, hits, fingerprint)
        val topThreat = hits.maxByOrNull { it.threat.ordinal }?.threat ?: Threat.INFO
        val classification = when {
            match != null -> "TRACKER"
            topThreat == Threat.PRIORITY -> "PRIORITY"
            topThreat == Threat.NOTABLE -> "NOTABLE"
            else -> "UNKNOWN"
        }

        val fix = fixHolder.current?.takeIf {
            val age = System.currentTimeMillis() - it.timestampMs
            age in 0..MAX_FIX_AGE_MS && it.accuracyM <= MAX_FIX_ACCURACY_M
        }
        val previous = deviceCache[id]
        // One stored observation per device per OBSERVATION_MIN_INTERVAL_MS keeps drive-by and
        // follower history dense enough (≈30 m apart at 60 km/h) without a row per advert.
        val recordObservation = ts - (lastObservationMs[id] ?: Long.MIN_VALUE / 2) >= OBSERVATION_MIN_INTERVAL_MS
        val updated = DeviceEntity(
                id = id, band = band.name, displayName = name, lastMac = id,
                firstSeenMs = previous?.firstSeenMs ?: ts, lastSeenMs = ts, lastRssi = rssi,
                classification = classification, trackerType = match?.type?.name,
                isAllowlisted = previous?.isAllowlisted ?: false,
                isOwnDevice = previous?.isOwnDevice ?: false,
                ownDeviceConfirmed = previous?.ownDeviceConfirmed ?: false,
                brand = identity.brand ?: previous?.brand,
                category = identity.category,
                observedLat = fix?.lat ?: previous?.observedLat,
                observedLon = fix?.lon ?: previous?.observedLon,
                observedAccuracyM = fix?.accuracyM ?: previous?.observedAccuracyM,
                observedAtMs = fix?.timestampMs ?: previous?.observedAtMs,
                radioDetails = radioDetails ?: previous?.radioDetails,
                kind = identity.kind.name,
                model = identity.model ?: previous?.model,
                seenCount = (previous?.seenCount ?: 0) + if (recordObservation) 1 else 0,
                rssiMin = minOf(previous?.rssiMin ?: rssi, rssi),
                rssiMax = maxOf(previous?.rssiMax ?: rssi, rssi),
                wifiSecurity = wifiSecurity ?: previous?.wifiSecurity,
                wifiBand = wifiBand ?: previous?.wifiBand,
            )
        deviceCache[id] = updated
        dirtyDevices[id] = updated
        onHomeDevice(id, updated, rssi, ts)
        if (recordObservation) {
            lastObservationMs[id] = ts
            pendingObservations += ObservationEntity(
                deviceId = id, band = band.name, rssi = rssi, timestampMs = ts,
                lat = fix?.lat, lon = fix?.lon, gpsAccuracyM = fix?.accuracyM,
                headingDeg = _heading.value.deg, sessionId = activeSessionId,
            )
            if (activeSessionId != null) {
                _scanSession.value = _scanSession.value.copy(observationCount = _scanSession.value.observationCount + 1)
            }
        }

        // Passive radio candidates on BLE and Wi-Fi. A nameless BLE beacon alone is too common
        // to treat as a hidden-camera finding, so BLE contributes only explicit camera/IoT rules.
        val findings = com.rfwatch.core.cs.RoomSweep.analyze(facts, hits)
            .filter { band == Band.WIFI || it.reason == com.rfwatch.core.cs.SweepReason.CAMERA_IOT_SIGNATURE }
        findings.forEach {
            val findingKey = it.key + ":" + it.reason.name
            roomMap[findingKey] = it
            roomLastSeenMs[findingKey] = ts
        }
        if (findings.isNotEmpty()) roomDirty = true

        if (huntTargetId == id) _hunt.value = HuntSample(rssi, ts)

        // Live sweep capture (every advert, not throttled like stored observations).
        if (sweepTargetId == id) {
            val h = guidedHeadingDeg ?: _heading.value.deg
            synchronized(sweepLock) {
                if (h != null) {
                    sweepHeadings.add(h); sweepRssi.add(rssi.toDouble()); sweepStepSamples++
                }
                _sweepLive.value = SweepLive(sweepHeadings.size, sweepStepSamples, rssi, h != null)
            }
        }

        // Radar contact (published by the throttled publisher).
        val prevContact = liveContacts[id]
        liveContacts[id] = (prevContact ?: LiveContact(id, band, name ?: id, classification)).copy(
            label = name ?: id, classification = classification,
            trackerType = match?.type, lastRssi = rssi, lastSeenMs = ts,
            brand = identity.brand ?: prevContact?.brand,
            category = identity.category,
            kind = identity.kind,
        )
        contactsDirty = true

        hits.firstOrNull { it.category == com.rfwatch.core.rules.RuleCategory.ATTACK_TOOLS }
            ?.let { hit -> onAttackTool(id, hit.label, "${hit.matchedOn} (rule ${hit.ruleId})", rssi, ts) }

        // Possible ambulance / fire equipment: gentle alert, once per device per cooldown.
        hits.filter { it.category == com.rfwatch.core.rules.RuleCategory.EMERGENCY_SERVICES }
            .maxByOrNull { it.threat.ordinal }
            ?.let { hit -> onEmergencyHit(id, hit, rssi, ts, updated) }

        // Possible law-enforcement equipment: grouped into encounters with a live notification.
        hits.filter { it.category == com.rfwatch.core.rules.RuleCategory.LAW_ENFORCEMENT }
            .maxByOrNull { it.threat.ordinal }
            ?.let { hit -> handleLawAction(lawTracker.onHit(id, hit.label, hit.threat, rssi, ts), hit.ruleId) }

        val trackerAlertConfirmed = match?.let {
            if (updated.isOwnDevice || updated.isAllowlisted) {
                trackerAlertGate.suppress(id)
                false
            } else {
                trackerAlertGate.shouldAlert(id, ts, it.confidence)
            }
        } ?: false
        maybeAlert(
            id, classification, match,
            trackerAlertConfirmed = trackerAlertConfirmed,
            cameraHit = hits.filter { it.category == com.rfwatch.core.rules.RuleCategory.CAMERA_IOT }.maxByOrNull { it.threat.ordinal },
            lawHit = hits.any { it.category == com.rfwatch.core.rules.RuleCategory.LAW_ENFORCEMENT },
            rssi = rssi,
            ownOrTrusted = updated.isOwnDevice || updated.isAllowlisted,
        )
    }

    private val lastAlertMs = HashMap<String, Long>()

    private suspend fun maybeAlert(
        id: String,
        classification: String,
        match: TrackerMatch?,
        trackerAlertConfirmed: Boolean,
        cameraHit: com.rfwatch.core.rules.RuleHit?,
        lawHit: Boolean,
        rssi: Int,
        ownOrTrusted: Boolean,
    ) {
        val now = System.currentTimeMillis()
        if (ownOrTrusted) return
        val relBearing = relativeBearingFor(id)
        // Law-enforcement hits alert only through the grouped encounter (LawEncounterTracker).
        // Cameras alert when close or when it is a spy-cam setup hotspot — neighbours' CCTV is common.
        val type = when {
            cameraHit != null && (cameraHit.threat == Threat.PRIORITY || rssi >= CAMERA_ALERT_MIN_RSSI) -> AlertType.CAMERA_IOT_SIGNATURE
            match != null && trackerAlertConfirmed -> AlertType.TRACKER_FOLLOWING
            classification == "PRIORITY" && !lawHit && cameraHit == null -> AlertType.PRIORITY_NEARBY
            else -> return
        }
        val cooldown = if (type == AlertType.CAMERA_IOT_SIGNATURE) CAMERA_ALERT_COOLDOWN_MS else ALERT_DEBOUNCE_MS
        if ((now - (lastAlertMs[id] ?: 0)) < cooldown) return
        val contact = _contacts.value[id] ?: liveContacts[id]
        val confidence = contact?.estimate?.confidence ?: 0.0
        alertManager.raise(type, titleFor(type), contact?.label, relBearing, confidence, speak = type != AlertType.TRACKER_FOLLOWING, deviceId = id)
        lastAlertMs[id] = now
        database.alertDao().insert(
            com.rfwatch.app.data.AlertEntity(
                type = type.name, timestampMs = now, deviceId = id,
                message = if (type == AlertType.TRACKER_FOLLOWING) {
                    "Persistent item-tracker-like BLE signature; this does not establish that it is following you."
                } else titleFor(type), bearingDeg = relBearing, confidence = confidence,
            )
        )
    }

    private val bleSpam = com.rfwatch.core.cs.BleSpamDetector()
    private val evilTwin = com.rfwatch.core.cs.EvilTwinDetector()
    private val lastToolAlertMs = HashMap<String, Long>()

    private suspend fun onBleSpam(d: com.rfwatch.core.cs.BleSpamDetection, ts: Long) {
        val detail = d.describe()
        alertManager.raise(AlertType.BLE_SPAM, titleFor(AlertType.BLE_SPAM), detail, null, 0.0)
        database.alertDao().insert(com.rfwatch.app.data.AlertEntity(type = AlertType.BLE_SPAM.name, timestampMs = ts, deviceId = null, message = detail))
        val fix = fixHolder.current
        database.eventDao().insert(com.rfwatch.app.data.EventEntity(kind = "BLE_SPAM", timestampMs = ts, detail = detail, lat = fix?.lat, lon = fix?.lon))
    }

    private suspend fun onEvilTwin(d: com.rfwatch.core.cs.EvilTwinDetection, bssid: String, rssi: Int, ts: Long) {
        val detail = d.describe() + " Signal $rssi dBm."
        alertManager.raise(AlertType.EVIL_TWIN, titleFor(AlertType.EVIL_TWIN), detail, null, 0.0)
        database.alertDao().insert(com.rfwatch.app.data.AlertEntity(type = AlertType.EVIL_TWIN.name, timestampMs = ts, deviceId = bssid, message = detail))
        val fix = fixHolder.current
        database.eventDao().insert(com.rfwatch.app.data.EventEntity(kind = "EVIL_TWIN", timestampMs = ts, deviceId = bssid, detail = detail, lat = fix?.lat, lon = fix?.lon))
    }

    /** Hacking-tool sighting: once per device per [TOOL_ALERT_COOLDOWN_MS], with the matched evidence. */
    private suspend fun onAttackTool(id: String, label: String, evidence: String, rssi: Int, ts: Long) {
        if (ts - (lastToolAlertMs[id] ?: 0L) < TOOL_ALERT_COOLDOWN_MS) return
        lastToolAlertMs[id] = ts
        val device = deviceCache[id]
        if (device?.isOwnDevice == true || device?.isAllowlisted == true) return
        val detail = "$label · $id · $rssi dBm (~${com.rfwatch.core.df.RssiCentroidLocator.rssiRangeM(rssi).toInt()} m) · matched on $evidence"
        alertManager.raise(AlertType.ATTACK_TOOL, titleFor(AlertType.ATTACK_TOOL), detail, null, 0.0, deviceId = id)
        database.alertDao().insert(com.rfwatch.app.data.AlertEntity(type = AlertType.ATTACK_TOOL.name, timestampMs = ts, deviceId = id, message = detail))
        val fix = fixHolder.current
        database.eventDao().insert(com.rfwatch.app.data.EventEntity(kind = "ATTACK_TOOL", timestampMs = ts, deviceId = id, detail = detail, lat = fix?.lat, lon = fix?.lon))
    }

    @Volatile private var lastEmergencyHitMs = 0L
    private val lastEmergencyAlertMs = HashMap<String, Long>()

    private suspend fun onEmergencyHit(id: String, hit: com.rfwatch.core.rules.RuleHit, rssi: Int, ts: Long, device: DeviceEntity) {
        lastEmergencyHitMs = ts
        if (hit.threat == Threat.INFO || device.isOwnDevice || device.isAllowlisted) return
        if (!alertManager.emergencyAlertsEnabled()) return
        if (ts - (lastEmergencyAlertMs[id] ?: 0L) < EMERGENCY_ALERT_COOLDOWN_MS) return
        lastEmergencyAlertMs[id] = ts
        val detail = "${hit.label} · ${device.displayName ?: id} · $rssi dBm (${hit.matchedOn})"
        alertManager.raise(AlertType.EMERGENCY_VEHICLE, titleFor(AlertType.EMERGENCY_VEHICLE), detail, null, 0.0, deviceId = id)
        database.alertDao().insert(com.rfwatch.app.data.AlertEntity(type = AlertType.EMERGENCY_VEHICLE.name, timestampMs = ts, deviceId = id, message = detail))
        val fix = fixHolder.current
        database.eventDao().insert(com.rfwatch.app.data.EventEntity(kind = "EMERGENCY", timestampMs = ts, deviceId = id, detail = detail, lat = fix?.lat, lon = fix?.lon))
    }

    /** Whether BLE scanning is actually running (Bluetooth off / failed are shown on Radar). */
    val bleScanState get() = bleScanner.state

    /** Builds the one-glance status for the home-screen widget and quick-settings tile. */
    fun publishStatus() {
        val now = System.currentTimeMillis()
        val running = com.rfwatch.app.monitor.MonitorService.running.value
        val law = _lawEncounter.value?.takeIf { it.active }
        val followers = _followers.value
        val drones = _drones.value.values.filter { now - it.lastSeenMs < 5 * 60_000L }
        val home = _home.value
        val headline = when {
            !running -> "Not monitoring"
            law != null -> "Police equipment nearby"
            followers.isNotEmpty() -> "${followers.size} travelling with you"
            drones.isNotEmpty() -> "Drone nearby"
            home.inside && home.newDevices.isNotEmpty() -> "New device at home"
            else -> "Monitoring · ${_contacts.value.size} signals"
        }
        val lines = buildList {
            law?.signatures?.take(2)?.forEach { add("• ${it.label.substringBefore(" (")} ${it.lastRssi} dBm") }
            if (followers.isNotEmpty()) add("• Travelling with you: ${followers.size}")
            if (drones.isNotEmpty()) add("• Drones: " + drones.joinToString { it.info.serial ?: "unidentified" })
            if (home.isSet) add("• Home watch: " + if (home.mature) "on" else "learning ${home.visitsLearned}/${com.rfwatch.core.cs.Baseline.MIN_SESSIONS}")
            if (running) add("• ${_contacts.value.count { it.value.kind == com.rfwatch.core.id.DeviceKind.TRACKER }} trackers in range")
        }
        runCatching {
            onStatus(com.rfwatch.app.widget.StatusSummary(running, headline, lines, law != null || followers.isNotEmpty() || drones.isNotEmpty()))
        }
    }

    private suspend fun handleLawAction(action: com.rfwatch.core.alerts.LawAction, ruleId: String? = null) {
        if (action == com.rfwatch.core.alerts.LawAction.NONE) return
        val encounter = lawTracker.snapshot() ?: return
        _lawEncounter.value = encounter
        val now = System.currentTimeMillis()
        val fix = fixHolder.current
        when (action) {
            com.rfwatch.core.alerts.LawAction.ALERT -> {
                alertManager.lawEncounter(encounter, loud = true, emergencyNearby = now - lastEmergencyHitMs < EMERGENCY_CONTEXT_MS)
                val strongest = encounter.strongest
                database.alertDao().insert(
                    com.rfwatch.app.data.AlertEntity(
                        type = (if (ruleId == "axon" || ruleId == "taser") AlertType.AXON_SIGNATURE else AlertType.LAW_ENFORCEMENT_SIGNATURE).name,
                        timestampMs = now, deviceId = strongest?.deviceId,
                        message = "Possible law-enforcement equipment: " + encounter.signatures.joinToString { it.label },
                        confidence = 0.0,
                    )
                )
            }
            com.rfwatch.core.alerts.LawAction.UPDATE -> alertManager.lawEncounter(encounter, loud = false, emergencyNearby = now - lastEmergencyHitMs < EMERGENCY_CONTEXT_MS)
            com.rfwatch.core.alerts.LawAction.ENDED -> {
                alertManager.lawEncounterEnded(encounter)
                database.eventDao().insert(
                    com.rfwatch.app.data.EventEntity(
                        kind = "LAW_ENCOUNTER", timestampMs = now, deviceId = encounter.strongest?.deviceId,
                        detail = "Possible law-enforcement equipment for ${(encounter.lastSeenMs - encounter.startedMs) / 1000} s: " +
                            encounter.signatures.joinToString { "${it.label} (max ${it.maxRssi} dBm)" },
                        lat = fix?.lat, lon = fix?.lon,
                    )
                )
            }
            com.rfwatch.core.alerts.LawAction.NONE -> Unit
        }
    }

    /** Bearing to the device relative to where the user is facing (0 = ahead), or null. */
    private fun relativeBearingFor(id: String): Double? {
        val absBearing = _contacts.value[id]?.estimate?.bearingFromUserDeg ?: return null
        val facing = _heading.value.deg ?: return null
        return ((absBearing - facing) % 360.0 + 360.0) % 360.0
    }

    private val cacheLoadMutex = Mutex()

    private suspend fun ensureDeviceCache() {
        if (deviceCacheLoaded) return
        cacheLoadMutex.withLock {
            if (deviceCacheLoaded) return
            // putIfAbsent: never let the stored row replace a fresher in-memory one.
            database.deviceDao().all().forEach { deviceCache.putIfAbsent(it.id, it) }
            deviceCacheLoaded = true
        }
    }

    private suspend fun loadActiveSession() {
        if (activeSessionId != null) return
        val session = database.sessionDao().active() ?: run {
            _scanSession.value = ScanSessionStatus()
            return
        }
        activeSessionId = session.id
        _scanSession.value = ScanSessionStatus(
            id = session.id,
            startedAtMs = session.startMs,
            observationCount = database.observationDao().countForSession(session.id),
        )
    }

    /** Writes cached device changes and buffered observations in one transaction. */
    private suspend fun flushPending() = writeMutex.withLock {
        if (dirtyDevices.isEmpty() && pendingObservations.isEmpty()) return@withLock
        val devices = dirtyDevices.values.toList()
        val observations = pendingObservations.toList()
        dirtyDevices.clear()
        pendingObservations.clear()
        database.withTransaction {
            if (devices.isNotEmpty()) database.deviceDao().upsertAll(devices)
            if (observations.isNotEmpty()) database.observationDao().insertAll(observations)
        }
        if (devices.isNotEmpty() && deviceCacheLoaded) publishDevices()
    }

    /**
     * Deletes observations, alerts and log events older than the user's retention window
     * (Settings). Devices — and with them the mine / trusted marks — are kept. Returns rows removed.
     */
    suspend fun purgeExpired(nowMs: Long = System.currentTimeMillis()): Int = withContext(ingestDispatcher) {
        flushPending()
        val days = retentionDays().coerceIn(1, 365)
        val cutoff = com.rfwatch.core.data.RetentionPolicy.cutoffMs(nowMs, days)
        var removed = 0
        database.withTransaction {
            removed = database.observationDao().purgeOlderThan(cutoff) +
                database.alertDao().purgeOlderThan(cutoff) +
                database.eventDao().purgeOlderThan(cutoff)
        }
        if (removed > 0) {
            database.eventDao().insert(
                com.rfwatch.app.data.EventEntity(kind = "RETENTION_PURGE", timestampMs = nowMs,
                    detail = "Removed $removed records older than $days days (devices and your marks kept)"),
            )
        }
        removed
    }

    /**
     * Sets the user's own-device / trusted (allowlist) flags. Goes through the device cache so
     * the next write-behind flush cannot overwrite the change with a stale row.
     */
    suspend fun setDeviceFlags(id: String, own: Boolean? = null, trusted: Boolean? = null) {
        withContext(ingestDispatcher) {
            ensureDeviceCache()
            val current = deviceCache[id] ?: return@withContext
            val updated = current.copy(
                isOwnDevice = own ?: current.isOwnDevice,
                ownDeviceConfirmed = own ?: current.ownDeviceConfirmed,
                isAllowlisted = trusted ?: current.isAllowlisted,
            )
            deviceCache[id] = updated
            dirtyDevices[id] = updated
            flushPending()
        }
    }

    /** Deletes every stored record and the in-memory caches built from them. */
    suspend fun wipeAllData() {
        withContext(ingestDispatcher) {
            writeMutex.withLock {
                deviceCache.clear(); dirtyDevices.clear(); pendingObservations.clear()
                lastObservationMs.clear(); liveContacts.clear(); contactsDirty = false
                activeSessionId = null
                _scanSession.value = ScanSessionStatus()
                roomMap.clear(); roomLastSeenMs.clear(); bleParseCache.clear()
                trackerAlertGate.reset()
                lawTracker.reset()
                homeFence = null; homeBaseline.clear(); homeVisits = 0; insideHome = false; visitDevices.clear()
                lastHomeAlertMs.clear(); lastFollowerAlertMs.clear(); lastDroneAlertMs.clear()
                lastAlertMs.clear()
                database.clearAllTables()
                _devices.value = emptyList()
            }
            _contacts.value = emptyMap()
            _room.value = emptyList()
            _lawEncounter.value = null
            _home.value = HomeStatus(); _followers.value = emptyMap(); _drones.value = emptyMap()
        }
    }

    /** Drops room findings not heard for ROOM_FINDING_TTL_MS and publishes the rest (≤1/s, not per advert). */
    private fun publishRoom(nowMs: Long) {
        val it = roomLastSeenMs.entries.iterator()
        while (it.hasNext()) {
            val e = it.next()
            if (nowMs - e.value > ROOM_FINDING_TTL_MS) { roomMap.remove(e.key); it.remove(); roomDirty = true }
        }
        if (!roomDirty) return
        roomDirty = false
        _room.value = roomMap.values.sortedByDescending { it.threat.ordinal }
    }

    /** Publishes radar contacts, keeping any direction-finding estimate set from the UI side. */
    private fun publishContacts() {
        if (!contactsDirty) return
        contactsDirty = false
        val snapshot = HashMap(liveContacts)
        _contacts.update { current ->
            snapshot.mapValues { (id, contact) -> contact.copy(estimate = current[id]?.estimate) }
        }
    }

    /** Collapse duplicate per-device GPS fixes and ignore sub-100 m jitter before path scoring. */
    private fun reduceUserTrack(points: List<UserPoint>): List<UserPoint> {
        val minuteAverages = points.groupBy { it.timestampMs / USER_TRACK_BUCKET_MS }
            .toSortedMap()
            .values
            .map { bucket ->
                UserPoint(
                    lat = bucket.map { it.lat }.average(),
                    lon = bucket.map { it.lon }.average(),
                    timestampMs = bucket.map { it.timestampMs }.average().toLong(),
                )
            }
        val reduced = ArrayList<UserPoint>()
        for (point in minuteAverages) {
            val previous = reduced.lastOrNull()
            if (previous == null || haversineM(previous.lat, previous.lon, point.lat, point.lon) >= USER_TRACK_MIN_STEP_M) {
                reduced += point
            }
        }
        return reduced
    }

    // ---- Sweep (step 2) ----

    /**
     * Starts capturing RSSI from [deviceId]. With [guided] the caller drives the heading via
     * [setGuidedHeading] (step sweep for phones without a compass); otherwise the compass is used.
     */
    fun startSweep(deviceId: String, guided: Boolean = false) {
        synchronized(sweepLock) {
            sweepHeadings.clear(); sweepRssi.clear(); sweepStepSamples = 0
            guidedHeadingDeg = if (guided) 0.0 else null
            sweepTargetId = deviceId
            _sweepLive.value = SweepLive(0, 0, null, guided || _heading.value.deg != null)
        }
        _sweep.value = null
    }

    /** Guided sweep: the user now faces [deg] clockwise from where they started. */
    fun setGuidedHeading(deg: Double) {
        synchronized(sweepLock) {
            guidedHeadingDeg = deg
            sweepStepSamples = 0
        }
    }

    fun finishSweep(): SweepResult? {
        val result = synchronized(sweepLock) {
            sweepTargetId = null
            guidedHeadingDeg = null
            if (sweepHeadings.size >= MIN_SWEEP_SAMPLES) {
                SweepFit.fit(sweepHeadings.toDoubleArray(), sweepRssi.toDoubleArray())
            } else null
        }
        _sweep.value = result
        return result
    }

    // ---- Drive-by (step 3) ----

    suspend fun estimatePosition(deviceId: String): EmitterEstimate {
        val obs = database.observationDao().geoForDevice(deviceId).map { it.toRadioObservation() }
        val estimate = DriveByEstimator.estimate(
            observations = obs,
            userFix = fixHolder.current,
            nowMs = System.currentTimeMillis(),
            previousEstimate = _contacts.value[deviceId]?.estimate,
        )
        _contacts.update { current ->
            val contact = current[deviceId] ?: return@update current
            current + (deviceId to contact.copy(estimate = estimate))
        }
        if (estimate.status == EstimateStatus.LOCATED) {
            database.eventDao().insert(
                com.rfwatch.app.data.EventEntity(
                    kind = "LOCATE", timestampMs = System.currentTimeMillis(), deviceId = deviceId,
                    detail = "Located ±${estimate.ellipse?.semiMajorM?.toInt()} m, conf ${"%.2f".format(estimate.confidence)}",
                    lat = estimate.lat, lon = estimate.lon,
                )
            )
        }
        return estimate
    }

    private fun ObservationEntity.toRadioObservation() = RadioObservation(
        deviceId = deviceId,
        band = if (band == Band.WIFI.name) Band.WIFI else Band.BLE,
        rssi = rssi,
        timestampMs = timestampMs,
        headingDeg = headingDeg,
        fix = if (lat != null && lon != null) GpsFix(lat, lon, gpsAccuracyM ?: 20.0, timestampMs) else null,
        rttDistanceM = rttDistanceM,
        rttStdDevM = rttStdM,
    )

    private fun titleFor(type: AlertType) = when (type) {
        AlertType.TRACKER_FOLLOWING -> "Possible item-tracker signal nearby"
        AlertType.PRIORITY_NEARBY -> "Priority device nearby"
        AlertType.PRIORITY_APPROACHING -> "Priority device approaching"
        AlertType.NEW_AT_HOME -> "New device at home"
        AlertType.SWEEP_HIT -> "Surveillance device"
        AlertType.IMSI_CATCHER -> "Possible IMSI catcher"
        AlertType.AXON_SIGNATURE -> "Possible Axon device signature nearby"
        AlertType.LAW_ENFORCEMENT_SIGNATURE -> "Possible law-enforcement radio signature nearby"
        AlertType.CAMERA_IOT_SIGNATURE -> "Possible camera / IoT radio signature nearby"
        AlertType.DRONE_NEARBY -> "Drone detected nearby"
        AlertType.POSSIBLE_FOLLOWER -> "Device travelling with you"
        AlertType.LAW_VEHICLE_FOLLOWING -> "Possible police equipment travelling with you"
        AlertType.EMERGENCY_VEHICLE -> "Ambulance / fire equipment nearby"
        AlertType.ATTACK_TOOL -> "Hacking tool nearby"
        AlertType.BLE_SPAM -> "Bluetooth spam attack nearby"
        AlertType.EVIL_TWIN -> "Possible fake Wi-Fi (evil twin)"
        AlertType.SKIMMER_SUSPECTED -> "Possible card skimmer nearby"
        AlertType.STALKERWARE -> "Possible stalkerware on this phone"
    }

    companion object {
        private const val ALERT_DEBOUNCE_MS = 60_000L
        private const val CAMERA_ALERT_MIN_RSSI = -75
        private const val EMERGENCY_ALERT_COOLDOWN_MS = 30 * 60_000L
        private const val TOOL_ALERT_COOLDOWN_MS = 60 * 60_000L
        private const val EMERGENCY_CONTEXT_MS = 3 * 60_000L
        private const val CAMERA_ALERT_COOLDOWN_MS = 6 * 60 * 60_000L
        const val MIN_SWEEP_SAMPLES = 6
        private const val FLUSH_INTERVAL_MS = 3_000L
        private const val RETENTION_FIRST_RUN_MS = 30_000L
        private const val RETENTION_INTERVAL_MS = 6 * 60 * 60_000L
        private val DISCOVERY_ORDER = compareByDescending<DeviceEntity> { it.firstSeenMs }.thenBy { it.id }
        private const val OBSERVATION_MIN_INTERVAL_MS = 2_000L
        private const val CONTACT_PUBLISH_INTERVAL_MS = 1_000L
        private const val LAW_TICK_MS = 5_000L
        private const val POSITION_DEVICE_LIMIT = 80
        private const val DRONE_TTL_MS = 5 * 60_000L
        private const val HOME_ID = "home"
        private const val VISITS_KEY = "__visits__"
        private const val HOME_RADIUS_M = 150.0
        private const val HOME_MIN_VISIT_MS = 20 * 60_000L
        private const val HOME_VISIT_ROLL_MS = 6 * 60 * 60_000L
        private const val HOME_BASELINE_MIN_HITS = 3
        private const val HOME_ALERT_MIN_HITS = 6
        private const val HOME_ALERT_MIN_SPAN_MS = 10 * 60_000L
        private const val HOME_ALERT_MIN_RSSI = -88
        private const val HOME_ALERT_COOLDOWN_MS = 24 * 60 * 60_000L
        private val HOME_CONCERNING_KINDS = setOf(
            com.rfwatch.core.id.DeviceKind.TRACKER, com.rfwatch.core.id.DeviceKind.FLEET,
            com.rfwatch.core.id.DeviceKind.CAMERA_IOT, com.rfwatch.core.id.DeviceKind.LAW_ENFORCEMENT,
            com.rfwatch.core.id.DeviceKind.DRONE, com.rfwatch.core.id.DeviceKind.HOTSPOT,
        )
        private const val FOLLOWER_CHECK_INTERVAL_MS = 2 * 60_000L
        private const val FOLLOWER_WINDOW_MS = 3 * 60 * 60_000L
        private const val FOLLOWER_MIN_HITS = 3
        private const val FOLLOWER_CANDIDATE_LIMIT = 40
        private const val FOLLOWER_ALERT_COOLDOWN_MS = 30 * 60_000L
        private const val DRONE_ALERT_COOLDOWN_MS = 10 * 60_000L
        private const val POSITION_SAMPLE_LIMIT = 120
        private const val LOCATE_SAMPLE_LIMIT = 400
        private const val CELL_SCAN_INTERVAL_MS = 6_000L
        private const val MAX_FIX_AGE_MS = 120_000L
        private const val MAX_FIX_ACCURACY_M = 100.0
        private const val ROOM_FINDING_TTL_MS = 120_000L
        private const val MAP_ESTIMATE_DEVICE_LIMIT = 24
        private const val MAP_ESTIMATE_SAMPLE_LIMIT = 240
        private const val FOLLOWER_DEVICE_HISTORY_LIMIT = 500
        private const val FOLLOWER_USER_TRACK_LIMIT = 10_000
        private const val USER_TRACK_BUCKET_MS = 60_000L
        private const val USER_TRACK_MIN_STEP_M = 100.0
    }
}
