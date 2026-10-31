package com.rfwatch.core.cs

/** Dampens noisy BLE tracker candidates; a packet signature alone never proves following. */
class TrackerAlertGate(
    private val minSightings: Int = 3,
    private val minPersistenceMs: Long = 60_000L,
    private val globalCooldownMs: Long = 30 * 60_000L,
    private val perDeviceCooldownMs: Long = 6 * 60 * 60_000L,
) {
    private data class Candidate(var firstSeenMs: Long, var lastSeenMs: Long, var sightings: Int)

    private val candidates = HashMap<String, Candidate>()
    private val lastAlertByDevice = HashMap<String, Long>()
    private var lastGlobalAlertMs = 0L

    /** True only after repeated observations spanning a minute and cooldown checks pass. */
    @Synchronized
    fun shouldAlert(deviceId: String, nowMs: Long, confidence: Double): Boolean {
        candidates.entries.removeAll { nowMs - it.value.lastSeenMs > CANDIDATE_TTL_MS }
        if (confidence < MIN_CONFIDENCE) return false

        val candidate = candidates[deviceId]
        val current = if (candidate == null || nowMs - candidate.lastSeenMs > CANDIDATE_TTL_MS) {
            Candidate(nowMs, nowMs, 1).also { candidates[deviceId] = it }
        } else {
            candidate.lastSeenMs = nowMs
            candidate.sightings++
            candidate
        }
        if (current.sightings < minSightings || nowMs - current.firstSeenMs < minPersistenceMs) return false
        if (lastGlobalAlertMs != 0L && nowMs - lastGlobalAlertMs < globalCooldownMs) return false
        val previousDeviceAlert = lastAlertByDevice[deviceId]
        if (previousDeviceAlert != null && nowMs - previousDeviceAlert < perDeviceCooldownMs) return false

        lastGlobalAlertMs = nowMs
        lastAlertByDevice[deviceId] = nowMs
        return true
    }

    /** Forgets all candidates and cooldowns (used when the user wipes their data). */
    @Synchronized
    fun reset() {
        candidates.clear()
        lastAlertByDevice.clear()
        lastGlobalAlertMs = 0L
    }

    companion object {
        private const val MIN_CONFIDENCE = 0.8
        private const val CANDIDATE_TTL_MS = 10 * 60_000L
    }
}
