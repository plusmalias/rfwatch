package com.rfwatch.app.ui

import com.rfwatch.app.data.DeviceEntity
import com.rfwatch.core.id.DeviceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceFiltersTest {
    private val now = 10 * 24 * 60 * 60_000L

    private fun dev(
        id: String, band: String = "BLE", kind: DeviceKind = DeviceKind.UNKNOWN, name: String? = null,
        firstAgoMin: Long = 60 * 48, lastAgoMin: Long = 0, rssi: Int = -70, seen: Int = 1,
        classification: String = "UNKNOWN", own: Boolean = false, trusted: Boolean = false,
    ) = DeviceEntity(
        id = id, band = band, displayName = name, lastMac = id,
        firstSeenMs = now - firstAgoMin * 60_000, lastSeenMs = now - lastAgoMin * 60_000, lastRssi = rssi,
        classification = classification, kind = kind.name, seenCount = seen,
        isOwnDevice = own, isAllowlisted = trusted,
    )

    private val inventory = listOf(
        dev("tag", kind = DeviceKind.TRACKER, classification = "TRACKER", rssi = -50, seen = 40, firstAgoMin = 30),
        dev("axon", kind = DeviceKind.LAW_ENFORCEMENT, name = "AXON Body 3", lastAgoMin = 90),
        dev("buds", kind = DeviceKind.AUDIO, name = "My Buds", own = true, rssi = -40),
        dev("router", band = "WIFI", kind = DeviceKind.NETWORK, name = "HomeNet", lastAgoMin = 60 * 30, seen = 900),
        dev("phone", kind = DeviceKind.PHONE, lastAgoMin = 1, rssi = -90),
    )

    private fun ids(f: DeviceFilter) = applyDeviceFilter(inventory, f, now).visible.map { it.id }

    @Test fun defaultKeepsDiscoveryOrderAndEverything() {
        assertEquals(listOf("tag", "axon", "buds", "router", "phone"), ids(DeviceFilter()))
    }

    @Test fun timeWindowsUseLastHeard() {
        assertEquals(listOf("tag", "buds", "phone"), ids(DeviceFilter(window = TimeWindow.LIVE)))
        assertEquals(listOf("tag", "axon", "buds", "phone"), ids(DeviceFilter(window = TimeWindow.TODAY)))
    }

    @Test fun threatsExcludeOwnAndIncludeLawAndTrackers() {
        assertEquals(listOf("tag", "axon"), ids(DeviceFilter(status = setOf(StatusFilter.THREATS))))
        val trustedTag = applyDeviceFilter(listOf(dev("t", kind = DeviceKind.TRACKER, classification = "TRACKER", trusted = true)),
            DeviceFilter(status = setOf(StatusFilter.THREATS)), now)
        assertTrue(trustedTag.visible.isEmpty())
    }

    @Test fun statusFiltersCombineWithAnd() {
        assertEquals(listOf("tag"), ids(DeviceFilter(status = setOf(StatusFilter.THREATS, StatusFilter.NEW))))
        assertEquals(listOf("axon", "router"), ids(DeviceFilter(status = setOf(StatusFilter.NAMED, StatusFilter.HIDE_MINE))))
    }

    @Test fun multipleKindsAreOrAndCountsIgnoreKindFilter() {
        val v = applyDeviceFilter(inventory, DeviceFilter(kinds = setOf(DeviceKind.TRACKER, DeviceKind.AUDIO)), now)
        assertEquals(listOf("tag", "buds"), v.visible.map { it.id })
        assertEquals(1, v.kindCounts[DeviceKind.PHONE])
        assertEquals(5, v.total)
    }

    @Test fun bandAndSearch() {
        assertEquals(listOf("router"), ids(DeviceFilter(band = BandFilter.WIFI)))
        assertEquals(listOf("axon"), ids(DeviceFilter(query = "  body ")))
        assertEquals(listOf("axon"), ids(DeviceFilter(query = "axon / law")))
    }

    @Test fun sorts() {
        assertEquals("buds", ids(DeviceFilter(sort = DeviceSort.STRONGEST)).first())
        assertEquals("router", ids(DeviceFilter(sort = DeviceSort.MOST_HEARD)).first())
        assertEquals(listOf("tag", "buds"), ids(DeviceFilter(sort = DeviceSort.LAST_HEARD)).take(2))
        val byName = ids(DeviceFilter(sort = DeviceSort.NAME))
        assertEquals(listOf("axon", "router", "buds"), byName.take(3))
        assertTrue(byName.takeLast(2).containsAll(listOf("tag", "phone")))
    }

    @Test fun clearKeepsSortOnly() {
        assertTrue(DeviceFilter(sort = DeviceSort.NAME).isDefault)
        assertTrue(!DeviceFilter(window = TimeWindow.LIVE).isDefault)
    }
}
