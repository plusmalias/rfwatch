package com.rfwatch.core.cs

/**
 * The learned set of devices normally present at one geofence (Home, Work), with how many
 * of the [sessions] observed sessions each device appeared in. Persisted by the :app layer.
 */
data class GeofenceBaseline(
    val geofenceId: String,
    val sessions: Int,
    val deviceSessionCounts: Map<String, Int>,
) {
    /** Fraction of sessions a device has been present for. */
    fun presence(deviceId: String): Double =
        if (sessions == 0) 0.0 else (deviceSessionCounts[deviceId] ?: 0).toDouble() / sessions
}

enum class AnomalyKind { NEW_DEVICE, PRIORITY_NEW_DEVICE, DISAPPEARED_REGULAR }

data class Anomaly(
    val geofenceId: String,
    val deviceId: String,
    val kind: AnomalyKind,
    val detail: String,
)

/**
 * Learns per-geofence baselines over successive sessions and flags anomalies against them.
 *
 * A baseline matures after [MIN_SESSIONS] visits. Once mature:
 *  - a device never (or rarely) seen before that is now classified PRIORITY or is a tracker
 *    raises [AnomalyKind.PRIORITY_NEW_DEVICE]; any other unseen device raises
 *    [AnomalyKind.NEW_DEVICE];
 *  - a device that was a regular ([REGULAR_PRESENCE] of sessions or more) but is absent this
 *    visit raises [AnomalyKind.DISAPPEARED_REGULAR] — a bug-sweep signal (a camera removed)
 *    as much as a convenience one.
 */
object Baseline {

    const val MIN_SESSIONS = 3
    const val REGULAR_PRESENCE = 0.6
    private const val KNOWN_PRESENCE = 0.2

    fun update(prev: GeofenceBaseline?, geofenceId: String, sessionDevices: Set<String>): GeofenceBaseline {
        val counts = HashMap(prev?.deviceSessionCounts ?: emptyMap())
        for (d in sessionDevices) counts[d] = (counts[d] ?: 0) + 1
        return GeofenceBaseline(geofenceId, (prev?.sessions ?: 0) + 1, counts)
    }

    fun anomalies(
        baseline: GeofenceBaseline,
        currentDevices: Set<String>,
        priorityDevices: Set<String>,
        trackerDevices: Set<String>,
    ): List<Anomaly> {
        if (baseline.sessions < MIN_SESSIONS) return emptyList()
        val out = ArrayList<Anomaly>()

        for (d in currentDevices) {
            val known = baseline.presence(d) >= KNOWN_PRESENCE
            if (!known) {
                if (d in priorityDevices || d in trackerDevices) {
                    out.add(Anomaly(baseline.geofenceId, d, AnomalyKind.PRIORITY_NEW_DEVICE,
                        if (d in trackerDevices) "new tracker at ${baseline.geofenceId}"
                        else "new priority device at ${baseline.geofenceId}"))
                } else {
                    out.add(Anomaly(baseline.geofenceId, d, AnomalyKind.NEW_DEVICE,
                        "new device at ${baseline.geofenceId}"))
                }
            }
        }

        for ((d, _) in baseline.deviceSessionCounts) {
            if (baseline.presence(d) >= REGULAR_PRESENCE && d !in currentDevices) {
                out.add(Anomaly(baseline.geofenceId, d, AnomalyKind.DISAPPEARED_REGULAR,
                    "regular device missing from ${baseline.geofenceId}"))
            }
        }
        return out
    }
}
