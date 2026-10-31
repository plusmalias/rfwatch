package com.rfwatch.app.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import com.rfwatch.core.cs.AdvParser
import com.rfwatch.core.cs.BleAdvertisement
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

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

/** Whether BLE scanning is actually running, for the UI. */
enum class BleScanState { SCANNING, BLUETOOTH_OFF, FAILED, STOPPED }

/**
 * Low-latency BLE advertisement scanner that keeps itself running:
 *  - follows Bluetooth on/off (a scanner obtained while Bluetooth was off, or before a toggle,
 *    is dead — previously that silently ended BLE scanning until the app restarted);
 *  - takes a fresh [BluetoothLeScanner] on every start and retries after [onScanFailed];
 *  - restarts every [REFRESH_MS], because Android downgrades unfiltered scans running longer
 *    than 30 minutes to opportunistic (almost no results).
 * Each result's raw bytes are parsed by the Android-free [AdvParser].
 */
class BleScanner(private val context: Context) {

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val _state = MutableStateFlow(BleScanState.STOPPED)
    val state: StateFlow<BleScanState> = _state.asStateFlow()

    @SuppressLint("MissingPermission") // caller gates on BLUETOOTH_SCAN
    fun scan(): Flow<BleSighting> = callbackFlow {
        var active: BluetoothLeScanner? = null
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        lateinit var callback: ScanCallback

        fun stop() {
            runCatching { active?.stopScan(callback) }
            active = null
        }

        fun start() {
            stop()
            val adapter = manager.adapter
            if (adapter == null || !adapter.isEnabled) { _state.value = BleScanState.BLUETOOTH_OFF; return }
            val scanner = adapter.bluetoothLeScanner ?: run { _state.value = BleScanState.BLUETOOTH_OFF; return }
            runCatching { scanner.startScan(null, settings, callback) }
                .onSuccess { active = scanner; _state.value = BleScanState.SCANNING }
                .onFailure { _state.value = BleScanState.FAILED }
        }

        callback = object : ScanCallback() {
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
                        com.rfwatch.core.math.Hex.upper(raw),
                    )
                )
            }

            override fun onBatchScanResults(results: MutableList<ScanResult>) {
                results.forEach { onScanResult(ScanSettings.CALLBACK_TYPE_ALL_MATCHES, it) }
            }

            override fun onScanFailed(errorCode: Int) {
                _state.value = BleScanState.FAILED
                active = null
                launch { delay(RETRY_MS); start() }
            }
        }

        val btReceiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)) {
                    BluetoothAdapter.STATE_ON -> launch { delay(ON_SETTLE_MS); start() }
                    BluetoothAdapter.STATE_TURNING_OFF, BluetoothAdapter.STATE_OFF -> { stop(); _state.value = BleScanState.BLUETOOTH_OFF }
                }
            }
        }
        context.registerReceiver(btReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))
        start()
        val refresher = launch {
            while (isActive) {
                delay(REFRESH_MS)
                if (_state.value == BleScanState.SCANNING || _state.value == BleScanState.FAILED) start()
            }
        }
        awaitClose {
            refresher.cancel()
            runCatching { context.unregisterReceiver(btReceiver) }
            stop()
            _state.value = BleScanState.STOPPED
        }
    }

    private companion object {
        const val REFRESH_MS = 25 * 60_000L
        const val RETRY_MS = 5_000L
        const val ON_SETTLE_MS = 1_500L
    }
}
