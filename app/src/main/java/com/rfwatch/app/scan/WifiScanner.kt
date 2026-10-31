package com.rfwatch.app.scan

import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import com.rfwatch.core.rules.DeviceFacts
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** One Wi-Fi AP sighting, carrying the RTT-responder flag so the ranger can target it. */
data class WifiSighting(
    val facts: DeviceFacts,
    val bssid: String,
    val is80211mcResponder: Boolean,
    val timestampMs: Long,
)

/**
 * Wi-Fi scanner. Android throttles `startScan()` heavily (roughly 4 scans / 2 minutes on
 * recent versions); [throttleNoticeNeeded] is surfaced to the UI so the user can be told to
 * disable scan throttling in Developer Options or prefer BLE targets for sweeps.
 */
class WifiScanner(private val context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val throttleNoticeNeeded: Boolean get() = true // conservative; always warn on Wi-Fi sweeps

    @SuppressLint("MissingPermission") // caller gates on NEARBY_WIFI_DEVICES / location
    fun scans(): Flow<List<WifiSighting>> = callbackFlow {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                val now = System.currentTimeMillis()
                val results = runCatching { wifi.scanResults }.getOrDefault(emptyList())
                trySend(results.map { it.toSighting(now) })
            }
        }
        context.registerReceiver(receiver, IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION))
        @Suppress("DEPRECATION")
        wifi.startScan()
        awaitClose { runCatching { context.unregisterReceiver(receiver) } }
    }

    private fun ScanResult.toSighting(now: Long): WifiSighting {
        val ssidStr = wifiSsid()
        val hidden = ssidStr.isNullOrBlank()
        val rttResponder = android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P && is80211mcResponder
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
        )
    }

    @Suppress("DEPRECATION")
    private fun ScanResult.wifiSsid(): String? =
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            wifiSsid?.toString()?.trim('"')?.ifBlank { null }
        } else {
            SSID?.ifBlank { null }
        }
}
