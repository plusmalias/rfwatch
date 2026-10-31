package com.rfwatch.app.scan

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/** One device found by Classic (BR/EDR) inquiry. */
data class ClassicFound(
    val address: String,
    val name: String?,
    val deviceClass: Int?,
    val rssi: Int,
    /** BluetoothDevice.DEVICE_TYPE_* (CLASSIC / LE / DUAL / UNKNOWN). */
    val type: Int,
)

/**
 * On-demand Classic Bluetooth discovery. Card skimmers mostly use Classic serial modules, which
 * the always-on BLE scanner cannot see. Inquiry runs in ~12 s cycles and is restarted until
 * [durationMs] elapses; it briefly competes with BLE scanning, so it is only run when asked.
 */
class ClassicScanner(private val context: Context) {

    private val adapter: BluetoothAdapter? =
        (context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager).adapter

    @SuppressLint("MissingPermission") // caller gates on BLUETOOTH_SCAN / CONNECT
    fun discover(durationMs: Long = 25_000L): Flow<ClassicFound> = callbackFlow {
        val bt = adapter
        if (bt == null || !bt.isEnabled) { close(); return@callbackFlow }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                when (intent?.action) {
                    BluetoothDevice.ACTION_FOUND -> {
                        val device: BluetoothDevice = (if (android.os.Build.VERSION.SDK_INT >= 33)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)) ?: return
                        val rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE).toInt()
                        val cls: BluetoothClass? = if (android.os.Build.VERSION.SDK_INT >= 33)
                            intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS, BluetoothClass::class.java)
                        else @Suppress("DEPRECATION") intent.getParcelableExtra(BluetoothDevice.EXTRA_CLASS)
                        val name = intent.getStringExtra(BluetoothDevice.EXTRA_NAME) ?: runCatching { device.name }.getOrNull()
                        trySend(
                            ClassicFound(
                                device.address, name, cls?.let { it.deviceClass or (it.majorDeviceClass) },
                                if (rssi == Short.MIN_VALUE.toInt()) -100 else rssi,
                                runCatching { device.type }.getOrDefault(BluetoothDevice.DEVICE_TYPE_UNKNOWN),
                            )
                        )
                    }
                    BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> runCatching { bt.startDiscovery() }
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
        })
        runCatching { bt.cancelDiscovery(); bt.startDiscovery() }
        val timer = launch { delay(durationMs); close() }
        awaitClose {
            timer.cancel()
            runCatching { bt.cancelDiscovery() }
            runCatching { context.unregisterReceiver(receiver) }
        }
    }
}
