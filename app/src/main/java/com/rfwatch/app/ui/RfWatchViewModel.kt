package com.rfwatch.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.rfwatch.app.repo.LiveContact
import com.rfwatch.app.repo.RfWatchRepository
import com.rfwatch.app.data.ObservationEntity
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
    val room = repo.roomFindings
    val cell = repo.cellAssessment

    val devices = repo.deviceFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val alerts = repo.alertFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val events = repo.eventFlow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun startSweep(deviceId: String) = repo.startSweep(deviceId)
    fun finishSweep(): SweepResult? = repo.finishSweep()

    fun estimate(deviceId: String, onResult: (EmitterEstimate) -> Unit) {
        viewModelScope.launch { onResult(repo.estimatePosition(deviceId)) }
    }

    fun contactList(): List<LiveContact> = contacts.value.values.sortedByDescending { it.lastRssi }

    suspend fun recentObservations(deviceId: String): List<ObservationEntity> =
        repo.recentObservations(deviceId)

    suspend fun refreshMapEstimates() = repo.refreshMapEstimates()

    class Factory(private val repo: RfWatchRepository) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = RfWatchViewModel(repo) as T
    }
}
