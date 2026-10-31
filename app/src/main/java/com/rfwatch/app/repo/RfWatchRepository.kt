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
import com.rfwatch.core.cs.TrackerType
import com.rfwatch.core.df.DriveByEstimator
import com.rfwatch.core.df.EmitterEstimate
import com.rfwatch.core.df.EstimateStatus
import com.rfwatch.core.df.SweepFit
import com.rfwatch.core.df.SweepResult
import com.rfwatch.core.geo.angularDiffDeg
import com.rfwatch.core.model.Band
import com.rfwatch.core.model.GpsFix
import com.rfwatch.core.model.Heading
import com.rfwatch.core.model.HeadingSource
import com.rfwatch.core.model.RadioObservation
import com.rfwatch.core.model.SensorAccuracy
import com.rfwatch.core.rules.DeviceFacts
import com.rfwatch.core.rules.RuleEngine
import com.rfwatch.core.rules.Threat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

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
    private val stingray = StingrayDetector()
    private val fixHolder = LocationFixHolder()

    private val _heading = MutableStateFlow(Heading(null, HeadingSource.NONE, SensorAccuracy.UNRELIABLE))
    val heading: StateFlow<Heading> = _heading.asStateFlow()

    private val _contacts = MutableStateFlow<Map<String, LiveContact>>(emptyMap())
    val contacts: StateFlow<Map<String, LiveContact>> = _contacts.asStateFlow()

    private val _sweep = MutableStateFlow<SweepResult?>(null)
    val sweep: StateFlow<SweepResult?> = _sweep.asStateFlow()

    // Room-sweep findings, keyed by device, updated as Wi-Fi devices are seen.
    private val roomMap = HashMap<String, com.rfwatch.core.cs.SweepFinding>()
    private val _room = MutableStateFlow<List<com.rfwatch.core.cs.SweepFinding>>(emptyList())
    val roomFindings: StateFlow<List<com.rfwatch.core.cs.SweepFinding>> = _room.asStateFlow()

    private val _cell = MutableStateFlow<CellAssessment?>(null)
    val cellAssessment: StateFlow<CellAssessment?> = _cell.asStateFlow()

    @Volatile private var sweepTargetId: String? = null
    private val sweepHeadings = ArrayList<Double>()
    private val sweepRssi = ArrayList<Double>()

    val deviceFlow get() = database.deviceDao().observeAll()
    val alertFlow get() = database.alertDao().recent()
    val eventFlow get() = database.eventDao().recent()

    fun startMonitoring(scope: CoroutineScope) {
        scope.launch {
            locationProvider.fixes().collect { fix ->
                fixHolder.update(fix)
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
                onDevice(adv.address, Band.BLE, sighting.deviceName, adv.rssi, adv.timestampMs, facts, match)
            }
        }
        scope.launch {
            wifiScanner.scans().collect { list ->
                val now = System.currentTimeMillis()
                list.forEach { s ->
                    onDevice(s.bssid, Band.WIFI, s.facts.ssid, s.facts.rssi, now, s.facts, null)
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
        facts: DeviceFacts, match: TrackerMatch?,
    ) {
        val hits = ruleEngine.evaluate(facts)
        val topThreat = hits.maxByOrNull { it.threat.ordinal }?.threat ?: Threat.INFO
        val classification = when {
            match != null -> "TRACKER"
            topThreat == Threat.PRIORITY -> "PRIORITY"
            topThreat == Threat.NOTABLE -> "NOTABLE"
            else -> "UNKNOWN"
        }

        val fix = fixHolder.current
        database.deviceDao().upsert(
            DeviceEntity(
                id = id, band = band.name, displayName = name, lastMac = id,
                firstSeenMs = ts, lastSeenMs = ts, lastRssi = rssi,
                classification = classification, trackerType = match?.type?.name,
            )
        )
        database.observationDao().insert(
            ObservationEntity(
                deviceId = id, band = band.name, rssi = rssi, timestampMs = ts,
                lat = fix?.lat, lon = fix?.lon, gpsAccuracyM = fix?.accuracyM,
                headingDeg = _heading.value.deg,
            )
        )

        // Room-sweep findings (Wi-Fi: cameras/IoT, hidden SSIDs, strong source-less signals).
        if (band == Band.WIFI) {
            val findings = com.rfwatch.core.cs.RoomSweep.analyze(listOf(facts), ruleEngine)
            if (findings.isNotEmpty()) {
                findings.forEach { roomMap[it.key + ":" + it.reason.name] = it }
                _room.value = roomMap.values.sortedByDescending { it.threat.ordinal }
            }
        }

        // Live sweep capture.
        if (sweepTargetId == id) {
            _heading.value.deg?.let { h ->
                sweepHeadings.add(h); sweepRssi.add(rssi.toDouble())
            }
        }

        // Radar contact.
        _contacts.value = _contacts.value.toMutableMap().apply {
            val prev = this[id]
            put(id, (prev ?: LiveContact(id, band, name ?: id, classification)).copy(
                label = name ?: id, classification = classification,
                trackerType = match?.type, lastRssi = rssi, lastSeenMs = ts,
            ))
        }

        maybeAlert(id, classification, match, rssi)
    }

    private val lastAlertMs = HashMap<String, Long>()

    private suspend fun maybeAlert(id: String, classification: String, match: TrackerMatch?, rssi: Int) {
        val now = System.currentTimeMillis()
        if ((now - (lastAlertMs[id] ?: 0)) < ALERT_DEBOUNCE_MS) return
        val relBearing = relativeBearingFor(id)
        val type = when {
            match != null -> AlertType.TRACKER_FOLLOWING
            classification == "PRIORITY" -> AlertType.PRIORITY_NEARBY
            else -> return
        }
        val contact = _contacts.value[id]
        val confidence = contact?.estimate?.confidence ?: 0.0
        alertManager.raise(type, titleFor(type), contact?.label, relBearing, confidence)
        lastAlertMs[id] = now
        database.alertDao().insert(
            com.rfwatch.app.data.AlertEntity(
                type = type.name, timestampMs = now, deviceId = id,
                message = titleFor(type), bearingDeg = relBearing, confidence = confidence,
            )
        )
    }

    /** Bearing to the device relative to where the user is facing (0 = ahead), or null. */
    private fun relativeBearingFor(id: String): Double? {
        val absBearing = _contacts.value[id]?.estimate?.bearingFromUserDeg ?: return null
        val facing = _heading.value.deg ?: return null
        return ((absBearing - facing) % 360.0 + 360.0) % 360.0
    }

    // ---- Sweep (step 2) ----

    fun startSweep(deviceId: String) {
        sweepTargetId = deviceId
        sweepHeadings.clear(); sweepRssi.clear()
        _sweep.value = null
    }

    fun finishSweep(): SweepResult? {
        val result = if (sweepHeadings.size >= 6) {
            SweepFit.fit(sweepHeadings.toDoubleArray(), sweepRssi.toDoubleArray())
        } else null
        sweepTargetId = null
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
        _contacts.value = _contacts.value.toMutableMap().apply {
            this[deviceId]?.let { put(deviceId, it.copy(estimate = estimate)) }
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
        AlertType.TRACKER_FOLLOWING -> "Possible tracker"
        AlertType.PRIORITY_NEARBY -> "Priority device nearby"
        AlertType.PRIORITY_APPROACHING -> "Priority device approaching"
        AlertType.NEW_AT_HOME -> "New device at home"
        AlertType.SWEEP_HIT -> "Surveillance device"
        AlertType.IMSI_CATCHER -> "Possible IMSI catcher"
    }

    companion object {
        private const val ALERT_DEBOUNCE_MS = 60_000L
        private const val CELL_SCAN_INTERVAL_MS = 6_000L
    }
}
