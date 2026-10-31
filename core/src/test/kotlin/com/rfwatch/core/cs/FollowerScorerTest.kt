package com.rfwatch.core.cs

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowerScorerTest {

    // Three stops ~1.1 km apart along a line (0.01° lat ≈ 1113 m), over 20 minutes.
    private val stops = listOf(
        Triple(-37.810, 144.960, 0L),
        Triple(-37.820, 144.962, 10 * 60_000L),
        Triple(-37.830, 144.964, 20 * 60_000L),
    )

    private fun userTrack() = stops.map { UserPoint(it.first, it.second, it.third) }

    private fun followerSightings() = stops.map { Sighting(it.first, it.second, it.third, -70) }

    @Test
    fun truePositiveFlagsFollower() {
        val r = FollowerScorer.score("ff: follower", followerSightings(), userTrack())
        assertTrue("should flag follower; score=${r.score}, loc=${r.distinctLocations}, " +
            "sep=${r.maxSeparationM}, span=${r.spanMs}, travel=${r.userTravelM}", r.isFollower)
        assertEquals(3, r.distinctLocations)
        assertTrue(r.score >= FollowerScorer.SCORE_THRESHOLD)
    }

    @Test
    fun ownDeviceIsExcluded() {
        val r = FollowerScorer.score(
            "own", followerSightings(), userTrack(),
            exclusion = FollowerExclusion.OWN_DEVICE,
        )
        assertFalse(r.isFollower)
        assertEquals(0.0, r.score, 1e-9)
        assertEquals(FollowerExclusion.OWN_DEVICE, r.exclusion)
    }

    @Test
    fun stationaryInfrastructureIsExcluded() {
        val r = FollowerScorer.score(
            "router", followerSightings(), userTrack(),
            exclusion = FollowerExclusion.STATIONARY_INFRASTRUCTURE,
        )
        assertFalse(r.isFollower)
    }

    @Test
    fun oneLocationIsNotAFollower() {
        // Device heard many times but all in one place → gates fail.
        val oneSpot = (0 until 10).map { Sighting(-37.810, 144.960, it * 60_000L, -60) }
        val r = FollowerScorer.score("tv", oneSpot, userTrack())
        assertFalse(r.isFollower)
        assertEquals(1, r.distinctLocations)
    }
}
