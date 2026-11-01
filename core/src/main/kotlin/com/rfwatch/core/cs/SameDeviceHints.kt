package com.rfwatch.core.cs

import kotlin.math.abs

/** The few facts about a stored device that the same-device heuristics need. */
data class DeviceSummary(
    val id: String,
    /** "BLE" or "WIFI". */
    val band: String,
    val mac: String,
    /** Advertised name / SSID. */
    val name: String?,
    val brand: String?,
    val model: String?,
    /** DeviceKind name; "UNKNOWN" counts as not known. */
    val kind: String?,
    val firstSeenMs: Long,
    val lastSeenMs: Long,
    val lastRssi: Int,
)

enum class HintStrength { PROBABLY, POSSIBLY }

data class SameDeviceHint(val otherId: String, val strength: HintStrength, val reason: String)

/**
 * "Probably the same device as …" hints — shown to the user, never used to merge records or feed
 * alerts. Two cases:
 *
 *  - **Bluetooth address rotation.** Phones, watches and earbuds swap their random BLE address
 *    every ~15 min, so one device becomes a chain of short-lived rows. Rows are linked when they
 *    share a real signature (at least two of brand / model / kind / name known and identical), one
 *    starts within [MAX_ROTATION_GAP_MS] after the other went quiet (up to [OVERLAP_MS] early, for
 *    scan batching), and the signal carries on at a similar level (≤ [MAX_RSSI_STEP_DB]).
 *  - **One router, several radios.** Routers derive extra BSSIDs (5 GHz, guest, mesh backhaul) from
 *    their own address ([EvilTwinDetector.derivedFrom]).
 */
object SameDeviceHints {

    const val MAX_ROTATION_GAP_MS = 5 * 60_000L
    const val OVERLAP_MS = 60_000L
    const val MAX_RSSI_STEP_DB = 12
    /** Tighter limits for generic signatures (no name / model). */
    const val STRICT_GAP_MS = 2 * 60_000L
    const val STRICT_RSSI_STEP_DB = 8
    /** Different SSID on a derived BSSID counts only when the addresses are this close. */
    const val OTHER_SSID_MAX_TAIL = 16

    fun isRandomAddress(mac: String): Boolean =
        mac.substringBefore(':').toIntOrNull(16)?.let { (it and 0x02) != 0 } ?: false

    /** Identity signature for rotation matching, or null when too little is known to match on. */
    fun signature(d: DeviceSummary): String? {
        val parts = listOf(d.brand, d.model, d.kind?.takeIf { it != "UNKNOWN" }, d.name)
        if (parts.count { !it.isNullOrBlank() } < 2) return null
        return parts.joinToString("/") { it?.trim()?.lowercase() ?: "-" }
    }

    /**
     * Hints for [target] among [all]. [firstRssi] gives a device's first recorded RSSI (the
     * stored row only has the latest), or null when unknown — then the RSSI check falls back to
     * the other row's latest reading.
     */
    fun forDevice(target: DeviceSummary, all: Collection<DeviceSummary>, firstRssi: (String) -> Int?): List<SameDeviceHint> =
        when (target.band) {
            "BLE" -> rotations(target, all, firstRssi)
            "WIFI" -> routerRadios(target, all)
            else -> emptyList()
        }

    /** Ids whose first RSSI [forDevice] may ask for (same band + signature), so callers can prefetch. */
    fun rotationCandidates(target: DeviceSummary, all: Collection<DeviceSummary>): List<String> {
        if (target.band != "BLE" || !isRandomAddress(target.mac)) return emptyList()
        val sig = signature(target) ?: return emptyList()
        return all.filter { it.id != target.id && it.band == "BLE" && isRandomAddress(it.mac) && signature(it) == sig }.map { it.id } + target.id
    }

    /** Generic signature: nothing identifying beyond brand/kind (e.g. "Apple · phone" — dozens nearby). */
    private fun generic(d: DeviceSummary) = d.name.isNullOrBlank() && d.model.isNullOrBlank()

    private fun links(prev: DeviceSummary, next: DeviceSummary, firstRssi: (String) -> Int?): Boolean {
        val strict = generic(prev)
        val gapMax = if (strict) STRICT_GAP_MS else MAX_ROTATION_GAP_MS
        val rssiMax = if (strict) STRICT_RSSI_STEP_DB else MAX_RSSI_STEP_DB
        val start = firstRssi(next.id) ?: next.lastRssi
        return next.firstSeenMs >= prev.lastSeenMs - OVERLAP_MS &&
            next.firstSeenMs <= prev.lastSeenMs + gapMax &&
            next.lastSeenMs > prev.lastSeenMs &&
            next.firstSeenMs > prev.firstSeenMs &&
            abs(prev.lastRssi - start) <= rssiMax
    }

    /**
     * Rotation links are shown only when **mutual and unique**: [t]'s only plausible successor has
     * [t] as its only plausible predecessor (and vice versa). With many look-alikes around (a room
     * of iPhones) an ambiguous chain says nothing, so nothing is shown.
     */
    private fun rotations(t: DeviceSummary, all: Collection<DeviceSummary>, firstRssi: (String) -> Int?): List<SameDeviceHint> {
        val ids = rotationCandidates(t, all).toSet()
        if (ids.size < 2) return emptyList()
        val group = all.filter { it.id in ids }
        fun successors(d: DeviceSummary) = group.filter { it.id != d.id && links(d, it, firstRssi) }
        fun predecessors(d: DeviceSummary) = group.filter { it.id != d.id && links(it, d, firstRssi) }
        val label = listOfNotNull(t.brand, t.name ?: t.model, t.kind?.takeIf { it != "UNKNOWN" }?.lowercase()).distinct().joinToString(" · ")
        fun gapText(ms: Long) = if (ms <= 0) "straight away" else if (ms < 60_000) "${ms / 1000} s later" else "${ms / 60_000} min later"
        val out = ArrayList<SameDeviceHint>()
        successors(t).singleOrNull()?.takeIf { n -> predecessors(n).singleOrNull()?.id == t.id }?.let { n ->
            out += SameDeviceHint(
                n.id, HintStrength.PROBABLY,
                "Same signature ($label). This address went quiet and ${n.mac} appeared ${gapText(n.firstSeenMs - t.lastSeenMs)} " +
                    "at a similar signal (${t.lastRssi} → ${firstRssi(n.id) ?: n.lastRssi} dBm), with no other look-alike in between " +
                    "— looks like its next rotating address.",
            )
        }
        predecessors(t).singleOrNull()?.takeIf { p -> successors(p).singleOrNull()?.id == t.id }?.let { p ->
            out += SameDeviceHint(
                p.id, HintStrength.PROBABLY,
                "Same signature ($label). ${p.mac} went quiet and this address appeared ${gapText(t.firstSeenMs - p.lastSeenMs)} " +
                    "at a similar signal (${p.lastRssi} → ${firstRssi(t.id) ?: t.lastRssi} dBm), with no other look-alike in between " +
                    "— looks like its previous rotating address.",
            )
        }
        return out
    }

    private fun normSsid(s: String?): String? =
        s?.trim()?.lowercase()?.replace(Regex("[ _-]?(5g|5ghz|2g|2\\.4g|2\\.4ghz|24g|6g|6ghz|ext|plus|_nomap)$"), "")?.takeIf { it.isNotEmpty() }

    private fun routerRadios(t: DeviceSummary, all: Collection<DeviceSummary>): List<SameDeviceHint> =
        all.filter { it.id != t.id && it.band == "WIFI" && (EvilTwinDetector.derivedFrom(it.mac, t.mac) || sameFirstFive(it.mac, t.mac)) }
            .mapNotNull { o ->
                val related = normSsid(o.name) != null && normSsid(o.name) == normSsid(t.name)
                if (!related && tailDistance(o.mac, t.mac) > OTHER_SSID_MAX_TAIL) return@mapNotNull null
                SameDeviceHint(
                    o.id, if (related) HintStrength.PROBABLY else HintStrength.POSSIBLY,
                    (if (related) "Same network \"${o.name}\" from a near-identical address (${o.mac})"
                    else "\"${o.name ?: "hidden network"}\" comes from a near-identical address (${o.mac})") +
                        " — routers give each radio / band / guest network its own address derived from one base address.",
                )
            }

    private fun tailDistance(a: String, b: String): Int {
        fun tail(m: String) = m.split(':', '-').takeLast(3).joinToString("").toIntOrNull(16)
        val x = tail(a) ?: return Int.MAX_VALUE; val y = tail(b) ?: return Int.MAX_VALUE
        return abs(x - y)
    }

    private fun sameFirstFive(a: String, b: String): Boolean =
        a.length == 17 && b.length == 17 && a.substring(0, 14).equals(b.substring(0, 14), ignoreCase = true)
}
