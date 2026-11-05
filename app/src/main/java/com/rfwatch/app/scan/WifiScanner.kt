package com.rfwatch.app.scan

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.SystemClock
import com.rfwatch.core.rules.DeviceFacts
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** One Wi-Fi AP sighting, carrying the RTT-responder flag so the ranger can target it. */
data class WifiSighting(
    val facts: DeviceFacts,
    val bssid: String,
    val is80211mcResponder: Boolean,
    val timestampMs: Long,
    val radioDetails: String,
    val capabilities: String = "",
    val frequencyMhz: Int = 0,
    /** Decoded ASTM Remote ID from a beacon vendor element (Wi-Fi Beacon Remote ID drones). */
    val remoteId: com.rfwatch.core.id.RemoteIdInfo? = null,
    /** DJI DroneID vendor element present (proprietary; not decoded). */
    val djiDroneId: Boolean = false,
    /** Pwnagotchi peer-advertisement element (IE 222, JSON) — its name if present. */
    val pwnagotchi: String? = null,
    /** Decoded WPS attributes and vendor-element hints (API 30+). */
    val ies: com.rfwatch.core.id.WifiIeInfo? = null,
)

/**
 * Wi-Fi scanner. Android throttles `startScan()` heavily (roughly 4 scans / 2 minutes on
 * recent versions); [throttleNoticeNeeded] is surfaced to the UI so the user can be told to
 * disable scan throttling in Developer Options or prefer BLE targets for sweeps.
 */
class WifiScanner(private val context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val throttleNoticeNeeded: Boolean get() = true // conservative; always warn on Wi-Fi sweeps

    private companion object {
        const val SCAN_INTERVAL_MS = 30_000L
        const val MAX_RESULT_AGE_MS = 120_000L
    }

    @SuppressLint("MissingPermission") // caller gates on NEARBY_WIFI_DEVICES / location
    fun scans(): Flow<List<WifiSighting>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val elapsedNowMs = SystemClock.elapsedRealtime()
                val wallNowMs = System.currentTimeMillis()
                val results = runCatching { wifi.scanResults }.getOrDefault(emptyList())
                val fresh = results.mapNotNull { result ->
                    val ageMs = (elapsedNowMs - result.timestamp / 1_000L).coerceAtLeast(0L)
                    if (ageMs > MAX_RESULT_AGE_MS) null else result.toSighting(wallNowMs - ageMs)
                }
                trySend(fresh)
            }
        }
        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        val scanRequester = launch {
            while (isActive) {
                @Suppress("DEPRECATION")
                runCatching { wifi.startScan() }
                delay(SCAN_INTERVAL_MS)
            }
        }
        awaitClose {
            scanRequester.cancel()
            runCatching { context.unregisterReceiver(receiver) }
        }
    }

    /** Remote ID / DJI DroneID vendor-specific elements (API 30+ exposes beacon IEs). */
    private fun ScanResult.droneVendorElements(): Pair<com.rfwatch.core.id.RemoteIdInfo?, Boolean> {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null to false
        var remoteId: com.rfwatch.core.id.RemoteIdInfo? = null
        var dji = false
        runCatching {
            informationElements.filter { it.id == 221 }.forEach { ie ->
                val buf = ie.bytes.duplicate()
                val body = ByteArray(buf.remaining()).also { buf.get(it) }
                if (body.size >= 3) {
                    val oui = body.copyOfRange(0, 3)
                    if (oui.contentEquals(com.rfwatch.core.id.RemoteIdParser.WIFI_OUI)) {
                        com.rfwatch.core.id.RemoteIdParser.parseWifiVendorElement(body)?.let { remoteId = remoteId?.merge(it) ?: it }
                    } else if (oui.contentEquals(com.rfwatch.core.id.RemoteIdParser.DJI_OUI)) dji = true
                }
            }
        }
        return remoteId to dji
    }

    /** Pwnagotchi announces itself with a custom information element 222 carrying JSON ("name": …). */
    private fun ScanResult.pwnagotchiName(): String? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return runCatching {
            informationElements.firstOrNull { it.id == 222 }?.let { ie ->
                val buf = ie.bytes.duplicate()
                val text = String(ByteArray(buf.remaining()).also { buf.get(it) }, Charsets.UTF_8)
                if ("pwnd_tot" in text || "pwnagotchi" in text || "\"name\"" in text)
                    Regex("\"name\"\\s*:\\s*\"([^\"]{1,40})\"").find(text)?.groupValues?.get(1) ?: "pwnagotchi"
                else null
            }
        }.getOrNull()
    }

    /** WPS model/device-name attributes and chipset vendor elements, decoded in :core. */
    private fun ScanResult.decodedIes(): com.rfwatch.core.id.WifiIeInfo? {
        if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.R) return null
        return runCatching {
            com.rfwatch.core.id.WifiIes.parse(informationElements.filter { it.id == 221 }.map { ie ->
                val buf = ie.bytes.duplicate()
                ie.id to ByteArray(buf.remaining()).also { buf.get(it) }
            })
        }.getOrNull()
    }

    private fun ScanResult.toSighting(now: Long): WifiSighting {
        val droneElements = droneVendorElements()
        val ies = decodedIes()
        val ssidStr = wifiSsid()
        val hidden = ssidStr.isNullOrBlank()
        val rttResponder = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && is80211mcResponder
        val channel = frequencyToChannel(frequency)
        val addressInfo = runCatching {
            val firstOctet = BSSID.substringBefore(':').toInt(16)
            if ((firstOctet and 0x02) != 0) "Locally administered BSSID (vendor OUI may be unavailable)" else "Globally administered BSSID"
        }.getOrDefault("BSSID type unavailable")
        val width = when (channelWidth) {
            ScanResult.CHANNEL_WIDTH_20MHZ -> "20 MHz"
            ScanResult.CHANNEL_WIDTH_40MHZ -> "40 MHz"
            ScanResult.CHANNEL_WIDTH_80MHZ -> "80 MHz"
            ScanResult.CHANNEL_WIDTH_160MHZ -> "160 MHz"
            ScanResult.CHANNEL_WIDTH_80MHZ_PLUS_MHZ -> "80+80 MHz"
            else -> null
        }
        val radioDetails = buildList {
            add("BSSID $BSSID")
            add("OUI ${BSSID.split(':').take(3).joinToString(":")}")
            add(addressInfo)
            add("Wi-Fi ${frequency} MHz${channel?.let { " · channel $it" } ?: ""}${width?.let { " · $it" } ?: ""}")
            add("Security/capabilities: ${capabilities.take(120)}")
            add(if (hidden) "SSID hidden" else "SSID $ssidStr")
            if (rttResponder) add("802.11mc RTT responder advertised")
            ies?.wps?.let { w ->
                listOfNotNull(
                    w.manufacturer?.let { "maker $it" }, w.modelName?.let { "model $it" }, w.modelNumber?.let { "number $it" },
                    w.deviceName?.let { "device name $it" }, w.category?.let { "type $it" },
                ).takeIf { it.isNotEmpty() }?.let { add("WPS: " + it.joinToString(", ")) }
            }
            ies?.vendors?.takeIf { it.isNotEmpty() }?.let { add("Vendor elements: " + it.joinToString()) }
        }.joinToString(" · ")
        return WifiSighting(
            facts = DeviceFacts(
                mac = BSSID,
                ssid = ssidStr,
                name = null,
                hiddenSsid = hidden,
                rssi = level,
            ),
            bssid = BSSID,
            is80211mcResponder = rttResponder,
            timestampMs = now,
            radioDetails = radioDetails,
            capabilities = capabilities.orEmpty(),
            frequencyMhz = frequency,
            remoteId = droneElements.first,
            djiDroneId = droneElements.second,
            pwnagotchi = pwnagotchiName(),
            ies = ies,
        )
    }

    private fun frequencyToChannel(mhz: Int): Int? = when {
        mhz == 2484 -> 14
        mhz in 2412..2472 && (mhz - 2407) % 5 == 0 -> (mhz - 2407) / 5
        mhz in 5000..5895 && (mhz - 5000) % 5 == 0 -> (mhz - 5000) / 5
        mhz in 5955..7115 && (mhz - 5950) % 5 == 0 -> (mhz - 5950) / 5
        else -> null
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.wifiSsid(): String? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            wifiSsid?.toString()?.trim('"')?.ifBlank { null }
        } else {
            SSID?.ifBlank { null }
        }
}
