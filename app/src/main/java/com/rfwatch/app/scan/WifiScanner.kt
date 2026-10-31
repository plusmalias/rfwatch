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

    private fun ScanResult.toSighting(now: Long): WifiSighting {
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
