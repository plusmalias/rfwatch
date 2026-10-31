package com.rfwatch.app.repo

import com.rfwatch.app.data.DeviceEntity
import com.rfwatch.app.data.ObservationEntity
import kotlin.math.roundToLong

/**
 * Debug diagnostic: measures where Map / Locate "duplicates" come from before anything is
 * merged. Counts and names only — never coordinates — so the log is safe to share.
 */
object DuplicateReport {

    data class Result(
        val mappedDevices: Int,
        val distinctMarkerPoints: Int,
        val largestStack: Int,
        val stacksOf5Plus: Int,
        val bleRandomAddresses: Int,
        /** Same BLE signature (brand/model/kind/name) under several random addresses whose lifetimes don't overlap. */
        val rotatingGroups: List<Pair<String, Int>>,
        /** One SSID on several BSSIDs, and BSSIDs sharing the first five octets (one AP, several radios). */
        val ssidGroups: List<Pair<String, Int>>,
        val sameApPrefixGroups: Int,
        /** Locate: per sampled device, sightings vs distinct GPS points. */
        val locateSamples: List<Triple<String, Int, Int>>,
    ) {
        fun lines(): List<String> = buildList {
            add("MAP: $mappedDevices mapped devices on $distinctMarkerPoints distinct points; largest stack $largestStack; stacks of 5+: $stacksOf5Plus")
            add("BLE: $bleRandomAddresses random (rotating-capable) addresses; ${rotatingGroups.size} signatures seen under 2+ non-overlapping addresses (sum ${rotatingGroups.sumOf { it.second }})")
            rotatingGroups.take(10).forEach { add("  rotating '${it.first}' × ${it.second}") }
            add("WIFI: ${ssidGroups.size} SSIDs on 2+ BSSIDs; $sameApPrefixGroups BSSID groups sharing 5 octets")
            ssidGroups.take(10).forEach { add("  ssid '${it.first}' × ${it.second}") }
            locateSamples.forEach { (name, total, distinct) ->
                add("LOCATE '$name': $total sightings on $distinct distinct GPS points (${if (total == 0) 0 else 100 - distinct * 100 / total}% repeats)")
            }
        }
    }

    private fun pointKey(lat: Double, lon: Double) = (lat * 1e5).roundToLong() to (lon * 1e5).roundToLong()

    private fun isRandomBle(mac: String): Boolean =
        runCatching { (mac.substringBefore(':').toInt(16) and 0x02) != 0 }.getOrDefault(false)

    fun analyze(devices: List<DeviceEntity>, locate: Map<DeviceEntity, List<ObservationEntity>>): Result {
        val mapped = devices.filter { it.observedLat != null && it.observedLon != null }
        val stacks = mapped.groupingBy { pointKey(it.observedLat!!, it.observedLon!!) }.eachCount().values

        val randomBle = devices.filter { it.band == "BLE" && isRandomBle(it.lastMac ?: it.id) && it.trackerType == null }
        val rotating = randomBle
            .groupBy { listOf(it.brand, it.model, it.kind, it.displayName).joinToString("/") { v -> v ?: "-" } }
            .filterKeys { key -> key.split("/").count { it != "-" } >= 2 } // need a real signature, not "unknown"
            .mapNotNull { (sig, rows) ->
                // Count addresses that follow one another in time (rotation), not ones alive together.
                val chain = rows.sortedBy { it.firstSeenMs }
                var sequential = 1
                for (i in 1 until chain.size) if (chain[i].firstSeenMs >= chain[i - 1].lastSeenMs - 60_000) sequential++
                if (sequential >= 2) sig to sequential else null
            }
            .sortedByDescending { it.second }

        val wifi = devices.filter { it.band == "WIFI" }
        val ssid = wifi.filter { !it.displayName.isNullOrBlank() }.groupBy { it.displayName!! }
            .mapValues { it.value.size }.filterValues { it >= 2 }.toList().sortedByDescending { it.second }
        val prefix = wifi.mapNotNull { it.lastMac?.uppercase()?.takeIf { m -> m.length == 17 }?.substring(0, 14) }
            .groupingBy { it }.eachCount().count { it.value >= 2 }

        val locateSamples = locate.map { (d, obs) ->
            val geo = obs.filter { it.lat != null && it.lon != null }
            Triple(d.displayName ?: d.kind ?: "device", geo.size, geo.map { pointKey(it.lat!!, it.lon!!) }.toSet().size)
        }
        return Result(
            mappedDevices = mapped.size,
            distinctMarkerPoints = stacks.size,
            largestStack = stacks.maxOrNull() ?: 0,
            stacksOf5Plus = stacks.count { it >= 5 },
            bleRandomAddresses = randomBle.size,
            rotatingGroups = rotating,
            ssidGroups = ssid,
            sameApPrefixGroups = prefix,
            locateSamples = locateSamples,
        )
    }
}
