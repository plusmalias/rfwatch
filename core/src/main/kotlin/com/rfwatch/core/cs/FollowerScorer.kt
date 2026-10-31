package com.rfwatch.core.cs

import com.rfwatch.core.geo.haversineM

/** One time-and-place a candidate device was heard. */
data class Sighting(
    val lat: Double,
    val lon: Double,
    val timestampMs: Long,
    val rssi: Int,
)

/** A place the user visited (derived from the GPS track). */
data class UserPoint(val lat: Double, val lon: Double, val timestampMs: Long)

/** Why a candidate was excluded from follower scoring. */
enum class FollowerExclusion { NONE, ALLOWLISTED, OWN_DEVICE, STATIONARY_INFRASTRUCTURE }

data class FollowerScore(
    val deviceId: String,
    val score: Double,
    val isFollower: Boolean,
    val distinctLocations: Int,
    val maxSeparationM: Double,
    val spanMs: Long,
    val userTravelM: Double,
    val coMovementRatio: Double,
    val exclusion: FollowerExclusion,
    /** Separated locations where the device was heard, newest-last, for the alert timeline. */
    val timeline: List<Sighting>,
)

/**
 * Scores whether a device is following the user. A device is a follower candidate only
 * when it has been seen across at least [MIN_DISTINCT_LOCATIONS] places that are each at
 * least [MIN_SEPARATION_M] apart, over at least [MIN_SPAN_MS], while the user themselves
 * travelled more than [MIN_USER_TRAVEL_M]. Those gates filter out the overwhelming case
 * of a device seen repeatedly in one place (a neighbour's TV, your own router).
 *
 * The four gated quantities are folded into a 0..1 score; above [SCORE_THRESHOLD] and with
 * no exclusion, the device is flagged as a possible follower.
 *
 * Exclusions short-circuit the score:
 *  - user allowlist,
 *  - auto-learned own-devices (see [OwnDeviceLearner]),
 *  - fixed infrastructure (the drive-by estimator classified it STATIONARY — a thing that
 *    does not move cannot follow you; you are passing a series of different ones).
 */
object FollowerScorer {

    const val MIN_DISTINCT_LOCATIONS = 3
    const val MIN_SEPARATION_M = 500.0
    const val MIN_SPAN_MS = 10 * 60 * 1000L
    const val MIN_USER_TRAVEL_M = 1000.0
    const val SCORE_THRESHOLD = 0.6

    fun score(
        deviceId: String,
        sightings: List<Sighting>,
        userTrack: List<UserPoint>,
        exclusion: FollowerExclusion = FollowerExclusion.NONE,
    ): FollowerScore {
        val sorted = sightings.sortedBy { it.timestampMs }
        val deviceClusters = separate(sorted.map { it.lat to it.lon })
        val distinct = deviceClusters.size
        val maxSep = maxPairwiseSeparation(deviceClusters)
        val span = if (sorted.size >= 2) sorted.last().timestampMs - sorted.first().timestampMs else 0L
        val userTravel = pathLength(userTrack.map { it.lat to it.lon })
        val userClusters = separate(userTrack.map { it.lat to it.lon })
        val coMovement = if (userClusters.isEmpty()) 0.0
            else (distinct.toDouble() / userClusters.size).coerceAtMost(1.0)

        val gatesMet = distinct >= MIN_DISTINCT_LOCATIONS &&
            maxSep >= MIN_SEPARATION_M &&
            span >= MIN_SPAN_MS &&
            userTravel > MIN_USER_TRAVEL_M

        val locationScore = (distinct.toDouble() / 5.0).coerceIn(0.0, 1.0)
        val distanceScore = (maxSep / 3000.0).coerceIn(0.0, 1.0)
        val durationScore = (span.toDouble() / (60 * 60 * 1000.0)).coerceIn(0.0, 1.0)
        val score = (0.35 * locationScore + 0.20 * distanceScore +
            0.15 * durationScore + 0.30 * coMovement).coerceIn(0.0, 1.0)

        val isFollower = exclusion == FollowerExclusion.NONE &&
            gatesMet && score >= SCORE_THRESHOLD

        return FollowerScore(
            deviceId = deviceId,
            score = if (exclusion == FollowerExclusion.NONE) score else 0.0,
            isFollower = isFollower,
            distinctLocations = distinct,
            maxSeparationM = maxSep,
            spanMs = span,
            userTravelM = userTravel,
            coMovementRatio = coMovement,
            exclusion = exclusion,
            timeline = sorted,
        )
    }

    /** Greedily reduce points to a set of representatives ≥ [MIN_SEPARATION_M] apart. */
    private fun separate(points: List<Pair<Double, Double>>): List<Pair<Double, Double>> {
        val reps = ArrayList<Pair<Double, Double>>()
        for (p in points) {
            if (reps.none { haversineM(it.first, it.second, p.first, p.second) < MIN_SEPARATION_M }) {
                reps.add(p)
            }
        }
        return reps
    }

    private fun maxPairwiseSeparation(pts: List<Pair<Double, Double>>): Double {
        var m = 0.0
        for (i in pts.indices) for (j in i + 1 until pts.size) {
            m = maxOf(m, haversineM(pts[i].first, pts[i].second, pts[j].first, pts[j].second))
        }
        return m
    }

    private fun pathLength(pts: List<Pair<Double, Double>>): Double {
        var d = 0.0
        for (i in 1 until pts.size) {
            d += haversineM(pts[i - 1].first, pts[i - 1].second, pts[i].first, pts[i].second)
        }
        return d
    }
}
