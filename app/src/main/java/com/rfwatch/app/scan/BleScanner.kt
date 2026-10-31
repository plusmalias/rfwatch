package com.rfwatch.app.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothManager
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.rfwatch.core.cs.AdvParser
import com.rfwatch.core.cs.BleAdvertisement
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/** A single BLE sighting: the neutral advertisement plus the raw [ScanResult]. */
data class BleSighting(
    val advertisement: BleAdvertisement,
    val deviceName: String?,
    val connectable: Boolean,
    val legacyAdvertisement: Boolean,
    val payloadLength: Int,
    val serviceUuids: List<String>,
    val serviceDataUuids: List<String>,
    /** Raw advertising bytes as uppercase hex; retained for local, read-only inspection. */
    val rawAdvertisingHex: String,
)

/**
 * Low-latency BLE advertisement scanner. Each result's raw advertising bytes are parsed by
 * the Android-free [AdvParser] into a [BleAdvertisement], so tracker classification is done
 * by the unit-tested :core parser rather than ad-hoc here.
 */
class BleScanner(context: Context) {

    private val scanner = (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager)
        .adapter?.bluetoothLeScanner

    @SuppressLint("MissingPermission") // caller gates on BLUETOOTH_SCAN
    fun scan(): Flow<BleSighting> = callbackFlow {
        if (scanner == null) { close(); return@callbackFlow }
        val callback = object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val raw = result.scanRecord?.bytes ?: return
                val adv = AdvParser.fromRawAdvBytes(
                    raw,
                    address = result.device.address,
                    rssi = result.rssi,
                    timestampMs = System.currentTimeMillis(),
                )
                trySend(
                    BleSighting(
                        adv,
                        result.scanRecord?.deviceName,
                        result.isConnectable,
                        result.isLegacy,
                        raw.size,
                        result.scanRecord?.serviceUuids.orEmpty().map { it.uuid.toString().uppercase() }.sorted(),
                        result.scanRecord?.serviceData?.keys.orEmpty().map { it.uuid.toString().uppercase() }.sorted(),
                        raw.joinToString(separator = "") { "%02X".format(it.toInt() and 0xFF) },
                    )
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        scanner.startScan(null, settings, callback)
        awaitClose { scanner.stopScan(callback) }
    }
}
