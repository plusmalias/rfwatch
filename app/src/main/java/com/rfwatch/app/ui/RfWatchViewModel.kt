package com.rfwatch.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rfwatch.app.repo.LiveContact
import com.rfwatch.app.repo.RfWatchRepository
import com.rfwatch.app.data.ObservationEntity
import com.rfwatch.core.cs.FollowerScore
import com.rfwatch.core.df.EmitterEstimate
import com.rfwatch.core.df.SweepResult
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
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
    val followers = repo.followers
    val homeStatus = repo.homeStatus

    /** One-shot request from another screen (car sweep, radar) to open Hunt on a device. */
    val huntRequest = kotlinx.coroutines.flow.MutableStateFlow<String?>(null)

    fun logCarSweep(lines: List<String>, heard: Int) { viewModelScope.launch { repo.logCarSweep(lines, heard) } }

    /** Build an evidence report and hand back the share-sheet intent. */
    fun exportEvidence(context: android.content.Context, deviceId: String?, onReady: (android.content.Intent) -> Unit) {
        viewModelScope.launch { onReady(repo.evidenceIntent(context.applicationContext, deviceId)) }
    }
    suspend fun refreshPositions(ids: Collection<String>) = repo.refreshPositions(ids)
    suspend fun locateData(deviceId: String) = repo.locateData(deviceId)
    val scanSession = repo.scanSession

    val devices = repo.deviceFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
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

    fun analyzeFollower(deviceId: String, onResult: (FollowerScore) -> Unit) {
        viewModelScope.launch { onResult(repo.analyzeFollower(deviceId)) }
    }

    class Factory(private val repo: RfWatchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RfWatchViewModel(repo) as T
    }
}
