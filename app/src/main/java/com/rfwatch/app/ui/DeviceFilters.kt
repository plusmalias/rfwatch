package com.rfwatch.app.ui

import com.rfwatch.app.data.DeviceEntity
import com.rfwatch.app.ui.screens.effectiveKind
import com.rfwatch.core.id.DeviceKind

/** How far back the Devices tab looks, by last time a device was heard. */
enum class TimeWindow(val label: String, val ms: Long?) {
    LIVE("Live", 2 * 60_000L),
    HOUR("1 h", 60 * 60_000L),
    TODAY("24 h", 24 * 60 * 60_000L),
    ALL("All", null),
}

/** Extra status filters; several can be on at once (AND). */
enum class StatusFilter(val label: String) {
    THREATS("Threats"),
    NEW("New 24 h"),
    HIDE_MINE("Hide mine / trusted"),
    NAMED("Named"),
}

enum class DeviceSort(val label: String) {
    DISCOVERY("Discovery"),
    STRONGEST("Strongest"),
    LAST_HEARD("Last heard"),
    MOST_HEARD("Most heard"),
    NAME("Name A–Z"),
}

enum class BandFilter(val label: String) { WIFI("Wi-Fi"), BLE("BLE") }

data class DeviceFilter(
    val query: String = "",
    /** Empty = every kind. */
    val kinds: Set<DeviceKind> = emptySet(),
    val band: BandFilter? = null,
    val window: TimeWindow = TimeWindow.ALL,
    val status: Set<StatusFilter> = emptySet(),
    val sort: DeviceSort = DeviceSort.DISCOVERY,
) {
    val isDefault: Boolean get() = this == DeviceFilter(sort = sort)
}

/** One filtered page of the inventory plus the counts the chips show. */
data class DeviceListView(
    val visible: List<DeviceEntity> = emptyList(),
    val total: Int = 0,
    /** Counts within the current window/status/band/search, before the kind filter. */
    val kindCounts: Map<DeviceKind, Int> = emptyMap(),
    val bandCounts: Map<BandFilter, Int> = emptyMap(),
    val statusCounts: Map<StatusFilter, Int> = emptyMap(),
)

/** Kinds that make a device worth a look even without a rule hit. */
private val THREAT_KINDS = setOf(
    DeviceKind.TRACKER, DeviceKind.LAW_ENFORCEMENT, DeviceKind.ATTACK_TOOL, DeviceKind.DRONE, DeviceKind.FLEET,
)
private val THREAT_CLASSES = setOf("TRACKER", "PRIORITY", "NOTABLE")
private const val NEW_WINDOW_MS = 24 * 60 * 60_000L

/**
 * Worth a look: not yours/trusted, and either a rule hit or a notable kind. A Find My broadcast
 * with its owner nearby is someone's own AirTag/iPhone travelling with them — like Apple's own
 * unwanted-tracking alerts, only the "separated from owner" state counts (measured on-device:
 * owner-nearby broadcasts made up most of 95 "threats" at home).
 */
internal fun isThreat(d: DeviceEntity, kind: DeviceKind): Boolean {
    if (d.isOwnDevice || d.isAllowlisted) return false
    val ownerNearby = listOfNotNull(d.model, d.category).any { "owner nearby" in it }
    if (ownerNearby && d.classification == "TRACKER" && kind == DeviceKind.TRACKER) return false
    // trackers.json "apple_findmy" matches Apple's company ID alone, so every iPhone/Mac/AirPod
    // comes out NOTABLE (measured: 63 of 63 remaining "threats" at home). Real Find My trackers
    // are caught by the tracker parser (classification TRACKER), so ignore that flag here.
    if (d.classification == "NOTABLE" && d.brand == "Apple" && kind in EVERYDAY_KINDS) return false
    return d.classification in THREAT_CLASSES || kind in THREAT_KINDS
}

private val EVERYDAY_KINDS = setOf(
    DeviceKind.PHONE, DeviceKind.COMPUTER, DeviceKind.AUDIO, DeviceKind.WEARABLE, DeviceKind.TV_MEDIA,
    DeviceKind.INPUT, DeviceKind.HEALTH, DeviceKind.UNKNOWN,
)

private fun searchText(d: DeviceEntity, kind: DeviceKind): String =
    listOfNotNull(d.displayName, d.id, d.lastMac, d.brand, d.category, d.band, d.trackerType, d.model, kind.label)
        .joinToString(" ").lowercase()

/**
 * Applies [filter] to [devices] (already in discovery order). Pure and allocation-light: kind is
 * resolved once per device, search text is built only when there is a query.
 */
fun applyDeviceFilter(devices: List<DeviceEntity>, filter: DeviceFilter, nowMs: Long): DeviceListView {
    val needle = filter.query.trim().lowercase()
    val windowStart = filter.window.ms?.let { nowMs - it }
    val kindCounts = HashMap<DeviceKind, Int>()
    val bandCounts = HashMap<BandFilter, Int>()
    val statusCounts = HashMap<StatusFilter, Int>()
    val visible = ArrayList<DeviceEntity>()
    for (d in devices) {
        if (windowStart != null && d.lastSeenMs < windowStart) continue
        val kind = d.effectiveKind()
        if (needle.isNotEmpty() && !searchText(d, kind).contains(needle)) continue
        val threat = isThreat(d, kind)
        val isNew = d.firstSeenMs >= nowMs - NEW_WINDOW_MS
        val mineOrTrusted = d.isOwnDevice || d.isAllowlisted
        val named = !d.displayName.isNullOrBlank()
        // Status counts ignore the status filter itself so each chip shows what it would add.
        if (threat) statusCounts.merge(StatusFilter.THREATS, 1, Int::plus)
        if (isNew) statusCounts.merge(StatusFilter.NEW, 1, Int::plus)
        if (mineOrTrusted) statusCounts.merge(StatusFilter.HIDE_MINE, 1, Int::plus)
        if (named) statusCounts.merge(StatusFilter.NAMED, 1, Int::plus)
        val passesStatus = filter.status.all {
            when (it) {
                StatusFilter.THREATS -> threat
                StatusFilter.NEW -> isNew
                StatusFilter.HIDE_MINE -> !mineOrTrusted
                StatusFilter.NAMED -> named
            }
        }
        if (!passesStatus) continue
        val band = when (d.band) { "WIFI" -> BandFilter.WIFI; "BLE" -> BandFilter.BLE; else -> null }
        band?.let { bandCounts.merge(it, 1, Int::plus) }
        if (filter.band != null && band != filter.band) continue
        kindCounts.merge(kind, 1, Int::plus)
        if (filter.kinds.isNotEmpty() && kind !in filter.kinds) continue
        visible += d
    }
    val sorted = when (filter.sort) {
        DeviceSort.DISCOVERY -> visible
        DeviceSort.STRONGEST -> visible.sortedByDescending { it.lastRssi }
        DeviceSort.LAST_HEARD -> visible.sortedByDescending { it.lastSeenMs }
        DeviceSort.MOST_HEARD -> visible.sortedByDescending { it.seenCount }
        DeviceSort.NAME -> visible.sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.displayName ?: "￿" + it.id })
    }
    return DeviceListView(sorted, devices.size, kindCounts, bandCounts, statusCounts)
}
