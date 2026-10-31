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

    val deviceFlow get() = database.deviceDao().observeAll()
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
            }
        }
        scope.launch {
            while (isActive) {
                delay(LAW_TICK_MS)
                handleLawAction(lawTracker.tick(System.currentTimeMillis()))
            }
        }
        scope.launch {
            locationProvider.fixes().collect { fix ->
                fixHolder.update(fix)
                _userLocation.value = fix
                headingProvider.updateFix(fix)
            }
        }
        scope.launch {
            headingProvider.headings().collect { _heading.value = it }
        }
        scope.launch {
            bleScanner.scan().collect { sighting ->
                val adv = sighting.advertisement
                val match = TrackerParser.parse(adv)
                val facts = DeviceFacts(
                    mac = adv.address,
                    ssid = null,
                    name = sighting.deviceName,
                    companyIds = adv.manufacturerData.keys,
                    serviceUuids = adv.serviceUuids16,
                    rssi = adv.rssi,
                )
                val addressType = runCatching {
                    val firstOctet = adv.address.substringBefore(':').toInt(16)
                    if ((firstOctet and 0x02) != 0) "Locally administered address (may be private/rotating)" else "Globally administered address"
                }.getOrDefault("Address type unavailable")
                val manufacturerData = adv.manufacturerData.toSortedMap().map { (companyId, bytes) ->
                    "0x${companyId.toString(16).uppercase().padStart(4, '0')}=${bytes.joinToString("") { "%02X".format(it.toInt() and 0xFF) }}"
                }
                val fingerprint = com.rfwatch.core.id.BleFingerprinter.fingerprint(adv, sighting.deviceName)
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
                    val fingerprint = com.rfwatch.core.id.WifiFingerprinter.fingerprint(s.facts.ssid, s.bssid)
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
        val findings = com.rfwatch.core.cs.RoomSweep.analyze(listOf(facts), ruleEngine)
            .filter { band == Band.WIFI || it.reason == com.rfwatch.core.cs.SweepReason.CAMERA_IOT_SIGNATURE }
        findings.forEach {
            val findingKey = it.key + ":" + it.reason.name
            roomMap[findingKey] = it
            roomLastSeenMs[findingKey] = ts
        }
        val stale = roomLastSeenMs.filterValues { ts - it > ROOM_FINDING_TTL_MS }.keys
        stale.forEach { roomLastSeenMs.remove(it); roomMap.remove(it) }
        if (findings.isNotEmpty() || stale.isNotEmpty()) {
            _room.value = roomMap.values.sortedByDescending { it.threat.ordinal }
        }

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
            cameraSignature = hits.any { it.category == com.rfwatch.core.rules.RuleCategory.CAMERA_IOT },
        )
    }

    private val lastAlertMs = HashMap<String, Long>()

    private suspend fun maybeAlert(
        id: String,
        classification: String,
        match: TrackerMatch?,
        trackerAlertConfirmed: Boolean,
        cameraSignature: Boolean,
    ) {
        val now = System.currentTimeMillis()
        if ((now - (lastAlertMs[id] ?: 0)) < ALERT_DEBOUNCE_MS) return
        val relBearing = relativeBearingFor(id)
        val type = when {
            cameraSignature -> AlertType.CAMERA_IOT_SIGNATURE
            match != null && trackerAlertConfirmed -> AlertType.TRACKER_FOLLOWING
            classification == "PRIORITY" -> AlertType.PRIORITY_NEARBY
            else -> return
        }
        val contact = _contacts.value[id] ?: liveContacts[id]
        val confidence = contact?.estimate?.confidence ?: 0.0
        alertManager.raise(type, titleFor(type), contact?.label, relBearing, confidence, speak = type != AlertType.TRACKER_FOLLOWING)
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

    private suspend fun handleLawAction(action: com.rfwatch.core.alerts.LawAction, ruleId: String? = null) {
        if (action == com.rfwatch.core.alerts.LawAction.NONE) return
        val encounter = lawTracker.snapshot() ?: return
        _lawEncounter.value = encounter
        val now = System.currentTimeMillis()
        val fix = fixHolder.current
        when (action) {
            com.rfwatch.core.alerts.LawAction.ALERT -> {
                alertManager.lawEncounter(encounter, loud = true)
                val strongest = encounter.strongest
                database.alertDao().insert(
                    com.rfwatch.app.data.AlertEntity(
                        type = (if (ruleId == "axon") AlertType.AXON_SIGNATURE else AlertType.LAW_ENFORCEMENT_SIGNATURE).name,
                        timestampMs = now, deviceId = strongest?.deviceId,
                        message = "Possible law-enforcement equipment: " + encounter.signatures.joinToString { it.label },
                        confidence = 0.0,
                    )
                )
            }
            com.rfwatch.core.alerts.LawAction.UPDATE -> alertManager.lawEncounter(encounter, loud = false)
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

    private suspend fun ensureDeviceCache() {
        if (deviceCacheLoaded) return
        database.deviceDao().all().forEach { deviceCache[it.id] = it }
        deviceCacheLoaded = true
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
                roomMap.clear(); roomLastSeenMs.clear()
                trackerAlertGate.reset()
                lawTracker.reset()
                lastAlertMs.clear()
                database.clearAllTables()
            }
            _contacts.value = emptyMap()
            _room.value = emptyList()
            _lawEncounter.value = null
        }
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
    }

    companion object {
        private const val ALERT_DEBOUNCE_MS = 60_000L
        const val MIN_SWEEP_SAMPLES = 6
        private const val FLUSH_INTERVAL_MS = 3_000L
        private const val OBSERVATION_MIN_INTERVAL_MS = 2_000L
        private const val CONTACT_PUBLISH_INTERVAL_MS = 1_000L
        private const val LAW_TICK_MS = 5_000L
        private const val POSITION_DEVICE_LIMIT = 80
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
