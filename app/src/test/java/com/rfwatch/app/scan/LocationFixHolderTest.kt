package com.rfwatch.app.scan

import com.rfwatch.core.model.GpsFix
import org.junit.Assert.assertEquals
import org.junit.Test

class LocationFixHolderTest {

    private var now = 0L
    private val holder = LocationFixHolder { now }
    private fun gps(lat: Double, acc: Double = 3.0) = GpsFix(lat, 0.0, acc, 0, provider = "gps")
    private fun net(lat: Double, acc: Double = 100.0) = GpsFix(lat, 0.0, acc, 0, provider = "network")

    @Test fun roughNetworkFixDoesNotReplaceFreshGps() {
        holder.update(gps(1.0)); now = 1_000
        holder.update(net(2.0))
        assertEquals(1.0, holder.current!!.lat, 0.0)
    }

    @Test fun networkFixUsedWhenGpsIsStale() {
        holder.update(gps(1.0)); now = 6_000
        holder.update(net(2.0))
        assertEquals(2.0, holder.current!!.lat, 0.0)
    }

    @Test fun networkFixUsedWithoutAnyGps() {
        holder.update(net(2.0))
        assertEquals(2.0, holder.current!!.lat, 0.0)
    }

    @Test fun moreAccurateNetworkFixStillWins() {
        holder.update(gps(1.0, acc = 40.0)); now = 1_000
        holder.update(net(2.0, acc = 15.0))
        assertEquals(2.0, holder.current!!.lat, 0.0)
    }

    @Test fun newGpsAlwaysReplaces() {
        holder.update(net(2.0)); now = 500
        holder.update(gps(1.0, acc = 30.0))
        assertEquals(1.0, holder.current!!.lat, 0.0)
    }
}
