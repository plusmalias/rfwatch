package com.rfwatch.core.cs

import com.rfwatch.core.rules.DeviceFacts
import com.rfwatch.core.rules.RuleEngine
import com.rfwatch.core.rules.Threat

enum class SweepReason {
    CAMERA_IOT_SIGNATURE,// matched a surveillance/IoT name or vendor rule
    HIDDEN_SSID,         // AP broadcasting no SSID
    STRONG_SOURCELESS,   // very strong signal with no identifiable source
}

/** One flagged item from a room sweep, always pairable with Hunt mode to locate it. */
data class SweepFinding(
    val key: String,
    val reason: SweepReason,
    val threat: Threat,
    val detail: String,
    val rssi: Int,
    val huntable: Boolean = true,
)

/**
 * Room-sweep analysis for checking accommodation (hotels, rentals) for surveillance.
 * Combines rule-pack matches (camera/IoT vendor OUIs and SSID patterns) with two
 * heuristics that catch devices trying not to be identified:
 *  - a hidden SSID (an AP deliberately not broadcasting its name), and
 *  - a very strong signal ([STRONG_RSSI] dBm or above) with no SSID/name, i.e. something
 *    powerful in the room that isn't announcing what it is.
 * Every finding is marked huntable so the user can walk it down with Hunt mode.
 */
object RoomSweep {

    const val STRONG_RSSI = -45

    fun analyze(devices: List<DeviceFacts>, engine: RuleEngine): List<SweepFinding> {
        val out = ArrayList<SweepFinding>()
        for (d in devices) out += analyze(d, engine.evaluate(d))
        return out.sortedByDescending { it.threat.ordinal * 1000 + (it.rssi + 100) }
    }

    /** Findings for one device from rule [hits] the caller already evaluated (no second rule pass). */
    fun analyze(d: DeviceFacts, hits: List<com.rfwatch.core.rules.RuleHit>): List<SweepFinding> {
        val key = d.mac ?: d.ssid ?: d.name ?: return emptyList()
        val out = ArrayList<SweepFinding>(2)
        hits.filter { it.category == com.rfwatch.core.rules.RuleCategory.CAMERA_IOT }.forEach {
            out.add(SweepFinding(key, SweepReason.CAMERA_IOT_SIGNATURE, it.threat, "${it.label} (${it.matchedOn})", d.rssi))
        }
        if (d.hiddenSsid) {
            out.add(SweepFinding(key, SweepReason.HIDDEN_SSID, Threat.NOTABLE, "Access point hiding its SSID", d.rssi))
        }
        val nameless = d.ssid.isNullOrBlank() && d.name.isNullOrBlank()
        if (d.rssi >= STRONG_RSSI && (nameless || d.hiddenSsid)) {
            out.add(SweepFinding(key, SweepReason.STRONG_SOURCELESS, Threat.PRIORITY,
                "Strong signal (${d.rssi} dBm) with no visible source", d.rssi))
        }
        return out
    }
}
