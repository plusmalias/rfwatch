package com.rfwatch.core.cs

import com.rfwatch.core.id.VendorDb

/** A burst of fake pairing pop-up adverts (Flipper / ESP32 "BLE spam"). */
data class BleSpamDetection(
    val distinctAddresses: Int,
    val adverts: Int,
    val windowMs: Long,
    val byType: Map<String, Int>,
    val strongestRssi: Int,
    val sampleAddresses: List<String>,
) {
    fun describe(): String =
        "$distinctAddresses different Bluetooth addresses sent $adverts pairing pop-up adverts in ${windowMs / 1000} s " +
            "(${byType.entries.sortedByDescending { it.value }.joinToString { "${it.key} ×${it.value}" }}), strongest $strongestRssi dBm. " +
            "Real accessories use one address each; dozens of rotating addresses advertising pop-ups is the signature of a BLE-spam tool. " +
            "Sample addresses: ${sampleAddresses.joinToString()}."
}

/**
 * Detects BLE pop-up spam: many *distinct* addresses advertising pairing-request payloads
 * (Apple proximity pairing / nearby action, Google Fast Pair, Samsung EasySetup, Microsoft
 * Swift Pair) within a short window. Genuine devices advertise these from one address at a time;
 * spam tools rotate the address on every packet to trigger pop-ups on every phone nearby.
 */
class BleSpamDetector(
    private val windowMs: Long = 10_000L,
    private val minDistinctAddresses: Int = 8,
    private val cooldownMs: Long = 5 * 60_000L,
) {
    private data class Hit(val address: String, val type: String, val rssi: Int, val ts: Long)

    private val hits = ArrayDeque<Hit>()
    private var lastAlertMs = Long.MIN_VALUE / 2

    /** Feed every pop-up-type advert; returns a detection when the burst threshold is crossed. */
    @Synchronized
    fun onPopupAdvert(address: String, type: String, rssi: Int, ts: Long): BleSpamDetection? {
        hits.addLast(Hit(address, type, rssi, ts))
        while (hits.isNotEmpty() && ts - hits.first().ts > windowMs) hits.removeFirst()
        val distinct = hits.map { it.address }.toSet()
        if (distinct.size < minDistinctAddresses || ts - lastAlertMs < cooldownMs) return null
        lastAlertMs = ts
        return BleSpamDetection(
            distinctAddresses = distinct.size,
            adverts = hits.size,
            windowMs = ts - hits.first().ts,
            byType = hits.groupingBy { it.type }.eachCount(),
            strongestRssi = hits.maxOf { it.rssi },
            sampleAddresses = distinct.take(5),
        )
    }

    companion object {
        /** Classify an advert as a pairing pop-up type, or null. Inputs are already-parsed AD fields. */
        fun popupType(adv: BleAdvertisement): String? {
            adv.manufacturerData[0x004C]?.let { d ->
                val type = d.firstOrNull()?.toInt()?.and(0xFF)
                if (type == 0x07) return "Apple proximity pairing"
                if (type == 0x0F) return "Apple nearby action"
            }
            if (adv.serviceData16.containsKey(0xFE2C)) return "Google Fast Pair"
            adv.manufacturerData[0x0075]?.let { d -> if (d.size >= 2 && (d[0].toInt() and 0xFF) == 0x42 && (d[1].toInt() and 0xFF) == 0x09) return "Samsung EasySetup" }
            adv.manufacturerData[0x0006]?.let { d -> if ((d.firstOrNull()?.toInt()?.and(0xFF)) == 0x03) return "Microsoft Swift Pair" }
            return null
        }
    }
}

/** One access point seen for an SSID. */
data class ApRecord(val bssid: String, val security: String, val vendor: String?, val randomised: Boolean, val firstSeenMs: Long)

data class EvilTwinDetection(val ssid: String, val suspect: ApRecord, val known: List<ApRecord>, val reasons: List<String>) {
    fun describe(): String =
        "\"$ssid\" is now also broadcast by ${suspect.bssid} (${suspect.security}, ${suspect.vendor ?: "unknown maker"}" +
            (if (suspect.randomised) ", randomised address" else "") + "). " +
            "Previously seen only from: " + known.joinToString { "${it.bssid} (${it.security}, ${it.vendor ?: "unknown maker"})" } + ". " +
            "Why flagged: ${reasons.joinToString("; ")}. Don't join it; if it's your own network, check your router."
}

/**
 * Learns which access points normally broadcast each SSID and flags an *evil twin*: a new BSSID
 * for a known SSID that is weaker-secured (e.g. WPA2 → open), or from a different manufacturer /
 * a randomised address when the known ones are a single real vendor. Mesh systems add BSSIDs from
 * the same vendor with the same security, which is not flagged. Hidden SSIDs are ignored.
 */
class EvilTwinDetector(private val minKnownAgeMs: Long = 10 * 60_000L) {
    private val bySsid = HashMap<String, MutableMap<String, ApRecord>>()
    private val alerted = HashSet<String>()

    /** Seed from stored history (BSSID, SSID, security) without alerting. */
    @Synchronized
    fun learn(ssid: String, bssid: String, security: String, ts: Long) {
        bySsid.getOrPut(ssid) { LinkedHashMap() }.putIfAbsent(bssid.lowercase(), record(bssid, security, ts))
    }

    @Synchronized
    fun observe(ssid: String?, bssid: String, security: String, ts: Long): EvilTwinDetection? {
        if (ssid.isNullOrBlank()) return null
        val aps = bySsid.getOrPut(ssid) { LinkedHashMap() }
        val key = bssid.lowercase()
        if (key in aps) return null
        val established = aps.values.filter { ts - it.firstSeenMs >= minKnownAgeMs }
        val rec = record(bssid, security, ts)
        aps[key] = rec
        if (established.isEmpty() || (ssid + key) in alerted) return null
        val reasons = ArrayList<String>()
        val knownSecure = established.all { rank(it.security) >= 2 }
        if (knownSecure && rank(rec.security) < established.minOf { rank(it.security) }) {
            reasons += "security dropped from ${established.joinToString("/") { it.security }} to ${rec.security}"
        }
        val knownVendors = established.mapNotNull { it.vendor }.toSet()
        val allKnownReal = established.none { it.randomised }
        if (allKnownReal && rec.randomised) reasons += "known access points use real manufacturer addresses, this one uses a randomised address"
        if (knownVendors.isNotEmpty() && rec.vendor != null && rec.vendor !in knownVendors) {
            reasons += "different manufacturer (${rec.vendor} vs ${knownVendors.joinToString()})"
        }
        // A different vendor alone is common (extenders); require a security drop or a randomised address too.
        val strong = reasons.any { it.startsWith("security") } || (rec.randomised && allKnownReal)
        if (!strong) return null
        alerted += ssid + key
        return EvilTwinDetection(ssid, rec, established, reasons)
    }

    private fun record(bssid: String, security: String, ts: Long): ApRecord {
        val first = bssid.substringBefore(':').toIntOrNull(16)
        return ApRecord(bssid, security, VendorDb.ouiVendor(bssid), first?.let(VendorDb::isLocallyAdministered) ?: false, ts)
    }

    /** Rough security strength: open 0, OWE/WEP 1, WPA 2, WPA2 3, WPA2/WPA3 4, WPA3/Enterprise 5. */
    private fun rank(label: String): Int {
        val l = label.lowercase()
        return when {
            "enterprise" in l || l == "wpa3" -> 5
            "wpa2/wpa3" in l -> 4
            "wpa2" in l -> 3
            l == "wpa" -> 2
            "wep" in l || "owe" in l || "enhanced" in l -> 1
            "open" in l -> 0
            else -> 2
        }
    }
}
