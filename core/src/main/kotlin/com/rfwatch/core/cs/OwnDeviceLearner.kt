package com.rfwatch.core.cs

/** A past monitoring session: when it happened and which devices it heard. */
data class SessionPresence(val startMs: Long, val deviceIds: Set<String>)

/** A device the app suspects belongs to the user, with the evidence behind the guess. */
data class OwnDeviceCandidate(
    val deviceId: String,
    val presenceRatio: Double,
    val sessionsPresent: Int,
    val sessionsConsidered: Int,
)

/**
 * Learns which devices are probably the user's own by looking at how consistently they
 * appear. A device heard in at least [PRESENCE_THRESHOLD] of sessions over the trailing
 * [WINDOW_DAYS] days (your earbuds, watch, car stereo) is a poor follower candidate — it
 * follows you because you carry it. Candidates are *offered* to the user for confirmation,
 * never auto-trusted, since a genuine long-term tracker would also score highly here.
 */
object OwnDeviceLearner {

    const val PRESENCE_THRESHOLD = 0.80
    const val WINDOW_DAYS = 7
    private const val DAY_MS = 24 * 60 * 60 * 1000L
    private const val MIN_SESSIONS = 3

    fun learn(sessions: List<SessionPresence>, nowMs: Long): List<OwnDeviceCandidate> {
        val cutoff = nowMs - WINDOW_DAYS * DAY_MS
        val window = sessions.filter { it.startMs >= cutoff }
        if (window.size < MIN_SESSIONS) return emptyList()
        val total = window.size
        val counts = HashMap<String, Int>()
        for (s in window) for (id in s.deviceIds) counts[id] = (counts[id] ?: 0) + 1
        return counts.entries
            .map { (id, c) -> OwnDeviceCandidate(id, c.toDouble() / total, c, total) }
            .filter { it.presenceRatio >= PRESENCE_THRESHOLD }
            .sortedByDescending { it.presenceRatio }
    }
}
