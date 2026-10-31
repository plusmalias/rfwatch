package com.rfwatch.core.cs

import com.rfwatch.core.math.TimeEwma
import kotlin.math.abs

/** One timestamped sighting of a tracker advertisement (its MAC may rotate between these). */
data class TrackerObservation(
    val address: String,
    val rssi: Int,
    val timestampMs: Long,
    val match: TrackerMatch,
)

/** A tracker followed across address rotations into a single virtual identity. */
data class LinkedIdentity(
    val id: Int,
    val type: TrackerType,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val addresses: Set<String>,
    val observations: Int,
    val lastRssi: Int,
)

/**
 * Links tracker sightings across MAC-address rotations into virtual identities, since the
 * tracker families rotate their addresses to defeat MAC correlation and a fresh MAC would
 * otherwise read as a brand-new device every few minutes.
 *
 * Heuristic: a sighting joins an existing identity of the **same [TrackerType]** when it
 * arrives within [maxGapMs] of that identity's last sighting and its RSSI is within
 * [maxRssiJumpDb] of the identity's smoothed RSSI. Otherwise it starts a new identity.
 *
 * Limits — this is a correlation heuristic, not proof of identity:
 *  - It can **over-merge**: two different trackers of the same brand that are both near
 *    you at similar signal strength may collapse into one identity.
 *  - It can **under-merge / split**: a tracker that drops out of range for longer than
 *    [maxGapMs], or whose RSSI jumps (you pocket the phone, a wall intervenes), starts a
 *    fresh identity, overcounting trackers.
 *  - Encrypted payloads mean we cannot confirm two MACs are the same unit; follower
 *    scoring therefore treats identities as weak evidence, corroborated by spatial/temporal
 *    co-movement rather than relied on alone.
 */
class TrackerIdentityLinker(
    private val maxGapMs: Long = 5 * 60_000L,
    private val maxRssiJumpDb: Int = 18,
) {

    private class Track(
        val id: Int,
        val type: TrackerType,
        var firstMs: Long,
        var lastMs: Long,
        val addresses: MutableSet<String>,
        var count: Int,
        val ewma: TimeEwma,
        var lastRssi: Int,
    )

    fun link(observations: List<TrackerObservation>): List<LinkedIdentity> {
        val sorted = observations.sortedBy { it.timestampMs }
        val tracks = ArrayList<Track>()
        var nextId = 0
        for (o in sorted) {
            val candidate = tracks
                .filter { it.type == o.match.type && (o.timestampMs - it.lastMs) <= maxGapMs }
                .minByOrNull { abs(it.ewma.current - o.rssi) }
            if (candidate != null && abs(candidate.ewma.current - o.rssi) <= maxRssiJumpDb) {
                candidate.lastMs = o.timestampMs
                candidate.addresses.add(o.address)
                candidate.count++
                candidate.ewma.update(o.rssi.toDouble(), o.timestampMs)
                candidate.lastRssi = o.rssi
            } else {
                val t = Track(
                    id = nextId++, type = o.match.type,
                    firstMs = o.timestampMs, lastMs = o.timestampMs,
                    addresses = mutableSetOf(o.address), count = 1,
                    ewma = TimeEwma(30_000.0).also { it.update(o.rssi.toDouble(), o.timestampMs) },
                    lastRssi = o.rssi,
                )
                tracks.add(t)
            }
        }
        return tracks.map {
            LinkedIdentity(it.id, it.type, it.firstMs, it.lastMs, it.addresses, it.count, it.lastRssi)
        }
    }
}
