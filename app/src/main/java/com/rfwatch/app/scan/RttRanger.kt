package com.rfwatch.app.scan

import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.net.wifi.rtt.RangingRequest
import android.net.wifi.rtt.RangingResult
import android.net.wifi.rtt.RangingResultCallback
import android.net.wifi.rtt.WifiRttManager
import android.os.Build
import androidx.annotation.RequiresApi
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/** A Wi-Fi RTT range to one AP: distance and its standard deviation, both in metres. */
data class RttRange(val bssid: String, val distanceM: Double, val stdDevM: Double, val timestampMs: Long)

/**
 * Wraps [WifiRttManager] (step 4). Ranges 802.11mc-responder APs so their distances can be
 * fed as hard constraints into the drive-by localiser. Entirely optional: gated behind
 * [isAvailable], which checks [PackageManager.FEATURE_WIFI_RTT]; on devices without it (most
 * budget phones, likely the Optus X Tap 3) the feature is simply absent and the rest of the
 * app is unaffected.
 */
class RttRanger(private val context: Context) {

    private val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    val isAvailable: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.P &&
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI_RTT) &&
            rttManager?.isAvailable == true

    private val rttManager: WifiRttManager?
        get() = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
            context.getSystemService(Context.WIFI_RTT_RANGING_SERVICE) as? WifiRttManager else null

    @RequiresApi(Build.VERSION_CODES.P)
    @SuppressLint("MissingPermission") // caller gates on location / NEARBY_WIFI_DEVICES
    suspend fun rangeResponders(): List<RttRange> {
        val mgr = rttManager ?: return emptyList()
        @Suppress("DEPRECATION")
        val responders = runCatching { wifi.scanResults }.getOrDefault(emptyList())
            .filter { it.is80211mcResponder }
            .take(RangingRequest.getMaxPeers())
        if (responders.isEmpty()) return emptyList()

        val request = RangingRequest.Builder().apply { addAccessPoints(ArrayList(responders)) }.build()
        return suspendCancellableCoroutine { cont ->
            mgr.startRanging(request, context.mainExecutor, object : RangingResultCallback() {
                override fun onRangingFailure(code: Int) { if (cont.isActive) cont.resume(emptyList()) }
                override fun onRangingResults(results: MutableList<RangingResult>) {
                    val now = System.currentTimeMillis()
                    val ranges = results
                        .filter { it.status == RangingResult.STATUS_SUCCESS }
                        .map {
                            RttRange(
                                bssid = it.macAddress?.toString() ?: "",
                                distanceM = it.distanceMm / 1000.0,
                                stdDevM = it.distanceStdDevMm / 1000.0,
                                timestampMs = now,
                            )
                        }
                    if (cont.isActive) cont.resume(ranges)
                }
            })
        }
    }
}
