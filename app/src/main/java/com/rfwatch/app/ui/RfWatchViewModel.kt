package com.rfwatch.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rfwatch.app.repo.LiveContact
import com.rfwatch.app.ui.screens.effectiveKind
import com.rfwatch.app.repo.RfWatchRepository
import com.rfwatch.app.data.ObservationEntity
import com.rfwatch.core.cs.FollowerScore
import com.rfwatch.core.df.EmitterEstimate
import com.rfwatch.core.df.SweepResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Exposes repository state to Compose and forwards user-driven DF actions. */
class RfWatchViewModel(private val repo: RfWatchRepository) : ViewModel() {

    val heading = repo.heading
    val userLocation = repo.userLocation
    val contacts = repo.contacts
    val sweep = repo.sweep
    val sweepLive = repo.sweepLive
    val hunt = repo.hunt
    fun setHuntTarget(deviceId: String?) = repo.setHuntTarget(deviceId)
    val hasCompass: Boolean get() = repo.hasCompass
    val room = repo.roomFindings
    val cell = repo.cellAssessment
    val lawEncounter = repo.lawEncounter
    val positions = repo.positions
    val drones = repo.drones
    val bleScanState = repo.bleScanState
    val followers = repo.followers
    val homeStatus = repo.homeStatus

    /** One-shot request from another screen (car sweep, radar) to open Hunt on a device. */
    val huntRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    /** Cross-screen device navigation (Details / Locate / Hunt / Map); MainActivity routes it. */
    private val _deviceNav = MutableStateFlow<DeviceNav?>(null)
    val deviceNav: StateFlow<DeviceNav?> = _deviceNav
    fun openDevice(deviceId: String, dest: DeviceDest) {
        if (dest == DeviceDest.HUNT) huntRequest.value = deviceId
        _deviceNav.value = DeviceNav(deviceId, dest)
    }
    /** Opens the Devices tab with [filter] applied (status chips on Radar use this). */
    private val _devicesRequest = MutableStateFlow(0L)
    val devicesRequest: StateFlow<Long> = _devicesRequest
    fun openDevicesWith(filter: DeviceFilter) {
        _deviceFilter.value = filter.copy(sort = _deviceFilter.value.sort)
        _devicesRequest.value = System.nanoTime()
    }

    /** Called by the screen that handled [nav]. */
    fun consumeNav(nav: DeviceNav) { _deviceNav.compareAndSet(nav, null) }

    /** Live radar contact for [id], or one rebuilt from the stored row when it is not live now. */
    fun contactFor(id: String): LiveContact? = contacts.value[id] ?: devices.value.firstOrNull { it.id == id }?.let { d ->
        LiveContact(
            id = d.id,
            band = runCatching { com.rfwatch.core.model.Band.valueOf(d.band) }.getOrDefault(com.rfwatch.core.model.Band.BLE),
            label = d.displayName ?: d.id, classification = d.classification,
            lastRssi = d.lastRssi, lastSeenMs = d.lastSeenMs, brand = d.brand, category = d.category,
            kind = d.effectiveKind(),
        )
    }

    /** Map shows only what the Devices filters let through (shared filter) when true. */
    val mapUsesDeviceFilter = MutableStateFlow(false)

    fun logUltrasonicCheck(r: com.rfwatch.core.cs.UltrasonicResult) { viewModelScope.launch { repo.logUltrasonicCheck(r) } }
    fun logCarSweep(lines: List<String>, heard: Int) { viewModelScope.launch { repo.logCarSweep(lines, heard) } }

    /** Build an evidence report and hand back the share-sheet intent. */
    fun exportEvidence(context: android.content.Context, deviceId: String?, onReady: (android.content.Intent) -> Unit) {
        viewModelScope.launch { onReady(repo.evidenceIntent(context.applicationContext, deviceId)) }
    }
    suspend fun refreshPositions(ids: Collection<String>) = repo.refreshPositions(ids)
    suspend fun locateData(deviceId: String) = repo.locateData(deviceId)
    val scanSession = repo.scanSession

    val devices = repo.deviceFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    /** Devices-tab filter; lives here so it survives tab switches. */
    private val _deviceFilter = MutableStateFlow(DeviceFilter())
    val deviceFilter: StateFlow<DeviceFilter> = _deviceFilter
    fun updateDeviceFilter(change: (DeviceFilter) -> DeviceFilter) = _deviceFilter.update(change)

    /** Re-evaluates time windows while no new rows arrive (e.g. scanning stopped). */
    private val filterClock = flow { while (true) { emit(System.currentTimeMillis()); delay(15_000) } }

    /** Filtered, sorted Devices-tab page, computed off the main thread. */
    val deviceView: StateFlow<DeviceListView> =
        combine(devices, _deviceFilter, filterClock) { list, filter, _ ->
            applyDeviceFilter(list, filter, System.currentTimeMillis())
        }
            .conflate()
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), DeviceListView())

    val alerts = repo.alertFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val events = repo.eventFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun startSweep(deviceId: String, guided: Boolean = false) = repo.startSweep(deviceId, guided)
    fun setGuidedHeading(deg: Double) = repo.setGuidedHeading(deg)
    fun finishSweep(): SweepResult? = repo.finishSweep()

    fun estimate(deviceId: String, onResult: (EmitterEstimate) -> Unit) {
        viewModelScope.launch { onResult(repo.estimatePosition(deviceId)) }
    }

    fun contactList(): List<LiveContact> = contacts.value.values.sortedByDescending { it.lastRssi }

    suspend fun recentObservations(deviceId: String): List<ObservationEntity> =
        repo.recentObservations(deviceId)

    suspend fun refreshMapEstimates() = repo.refreshMapEstimates()
    fun logRadarReport(ids: List<String>) { viewModelScope.launch { runCatching { repo.logRadarReport(ids) }.onFailure { android.util.Log.e("RFWatchRadar", "report failed", it) } } }
    suspend fun sameDeviceHints(id: String) = runCatching { repo.sameDeviceHints(id) }.getOrDefault(emptyList())
    fun logDuplicateReport() { viewModelScope.launch { runCatching { repo.logDuplicateReport() } } }

    fun startScanSession() {
        viewModelScope.launch { repo.startScanSession() }
    }

    fun saveScanSession(onSaved: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onSaved(repo.saveScanSession()) }
    }

    fun clearScanSession(onCleared: (Boolean) -> Unit = {}) {
        viewModelScope.launch { onCleared(repo.clearScanSession()) }
    }

    fun setDeviceFlags(id: String, own: Boolean? = null, trusted: Boolean? = null) {
        viewModelScope.launch { repo.setDeviceFlags(id, own, trusted) }
    }

    fun setNickname(id: String, nickname: String?) {
        viewModelScope.launch { repo.setNickname(id, nickname) }
    }

    fun analyzeFollower(deviceId: String, onResult: (FollowerScore) -> Unit) {
        viewModelScope.launch { onResult(repo.analyzeFollower(deviceId)) }
    }

    class Factory(private val repo: RfWatchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RfWatchViewModel(repo) as T
    }
}
