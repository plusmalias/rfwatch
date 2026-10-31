package com.rfwatch.app.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Where one device can be opened. Every screen that shows a device links to the others. */
enum class DeviceDest(val label: String) {
    DETAILS("Details"),
    LOCATE("Locate"),
    HUNT("Hunt"),
    MAP("Map"),
}

/** A one-shot request to open [deviceId] in [dest]; [stamp] makes repeat taps distinct. */
data class DeviceNav(val deviceId: String, val dest: DeviceDest, val stamp: Long = System.nanoTime())

/** The shared "open this device elsewhere" row: every destination except the current one. */
@Composable
fun DeviceLinks(vm: RfWatchViewModel, deviceId: String, here: DeviceDest?, modifier: Modifier = Modifier) {
    Row(modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DeviceDest.entries.filter { it != here }.forEach { dest ->
            OutlinedButton(
                onClick = { vm.openDevice(deviceId, dest) },
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
            ) { Text(dest.label, fontSize = 12.sp) }
        }
    }
}
