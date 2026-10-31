package com.rfwatch.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.RfWatchApp
import com.rfwatch.app.scan.ClassicFound
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.OkGreen
import com.rfwatch.app.ui.theme.WarnAmber
import com.rfwatch.core.cs.SkimmerAssessment
import com.rfwatch.core.cs.SkimmerLevel
import com.rfwatch.core.cs.SkimmerScorer
import kotlinx.coroutines.launch

private const val CHECK_MS = 25_000L

/**
 * On-demand card-skimmer check for fuel pumps, ATMs and EFTPOS terminals: runs Classic Bluetooth
 * discovery (which the always-on BLE scan can't see), scores every device with [SkimmerScorer]
 * and shows the full point-by-point evidence. HIGH results raise a red flashing alert.
 */
@Composable
fun SkimmerScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as RfWatchApp).container }
    val scope = rememberCoroutineScope()
    val found = remember { mutableStateMapOf<String, ClassicFound>() }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var finishedAt by remember { mutableStateOf<Long?>(null) }

    val assessed = found.values.map { f ->
        f to SkimmerScorer.score(f.address, f.name, f.deviceClass, f.rssi, classicOnly = f.type == android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC)
    }.sortedByDescending { it.second.score }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("SKIMMER CHECK", fontSize = 10.sp, color = DangerRed, letterSpacing = 1.2.sp)
            Text("Fuel pump · ATM · EFTPOS", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Stand right at the pump / ATM and run the check. Most documented card skimmers use cheap Classic-Bluetooth " +
                    "serial modules (HC-05/HC-06, RN-42) that the normal scan can't see. Each device is scored on its name, device class, " +
                    "address block and signal strength — every point is shown so you can judge it yourself.",
                fontSize = 12.sp, color = ConsoleMuted,
            )
        }
        item {
            Button(
                enabled = !running,
                onClick = {
                    found.clear(); running = true; progress = 0f; finishedAt = null
                    val started = System.currentTimeMillis()
                    scope.launch {
                        val ticker = launch {
                            while (true) {
                                progress = ((System.currentTimeMillis() - started).toFloat() / CHECK_MS).coerceAtMost(1f)
                                kotlinx.coroutines.delay(250)
                            }
                        }
                        container.classicScanner.discover(CHECK_MS).collect { f ->
                            val prev = found[f.address]
                            found[f.address] = if (prev == null) f else f.copy(rssi = maxOf(prev.rssi, f.rssi), name = f.name ?: prev.name)
                        }
                        ticker.cancel()
                        running = false; progress = 1f; finishedAt = System.currentTimeMillis()
                        val results = found.values.map {
                            SkimmerScorer.score(it.address, it.name, it.deviceClass, it.rssi, it.type == android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC)
                        }
                        container.repository.logSkimmerCheck(results, found.size)
                    }
                },
            ) { Text(if (running) "Scanning… keep the phone at the terminal" else "Run skimmer check (25 s)") }
            if (running || finishedAt != null) {
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
            }
        }
        if (finishedAt != null) {
            item {
                val high = assessed.count { it.second.level == SkimmerLevel.HIGH }
                val medium = assessed.count { it.second.level == SkimmerLevel.MEDIUM }
                Text(
                    when {
                        high > 0 -> "⚠ $high HIGH-likelihood skimmer module(s). Don't use this terminal; tell staff and report it."
                        medium > 0 -> "$medium device(s) worth a closer look — see the evidence below."
                        found.isEmpty() -> "No Classic Bluetooth devices heard. Note: some skimmers use no radio at all (they're collected physically) — still check for loose or bulky card readers."
                        else -> "Nothing skimmer-like among ${found.size} device(s). Physical checks still matter."
                    },
                    fontSize = 14.sp, color = if (high > 0) DangerRed else if (medium > 0) WarnAmber else OkGreen,
                )
            }
        }
        items(assessed, key = { it.first.address }) { (f, a) -> SkimmerRow(f, a) }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = ConsolePanel), border = BorderStroke(1.dp, ConsoleOutline)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Physical checks (catch radio-less skimmers too)", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    listOf(
                        "Wiggle the card slot and keypad — overlays move, are bulky, or have mismatched colours.",
                        "Fuel pumps: look for broken or missing security tape on the dispenser door.",
                        "Prefer tap-to-pay; cover the keypad when entering a PIN (hidden cameras).",
                        "If something looks wrong: don't use it, tell staff, and report to police with this check's event-log entry.",
                    ).forEach { Text("• $it", fontSize = 12.sp, color = ConsoleMuted) }
                }
            }
        }
    }
}

@Composable
private fun SkimmerRow(f: ClassicFound, a: SkimmerAssessment) {
    val color = when (a.level) { SkimmerLevel.HIGH -> DangerRed; SkimmerLevel.MEDIUM -> WarnAmber; SkimmerLevel.LOW -> ConsoleMuted }
    var expanded by remember(f.address) { mutableStateOf(a.level == SkimmerLevel.HIGH) }
    Card(colors = CardDefaults.cardColors(containerColor = ConsolePanel), border = BorderStroke(1.dp, color.copy(alpha = 0.6f))) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("${a.level} · ${a.score}/100", color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${f.rssi} dBm", fontSize = 12.sp, color = ConsoleMuted)
            }
            Text(f.name ?: "(no name)", fontSize = 14.sp)
            Text(
                "${f.address} · ${a.vendor ?: "address not in IEEE registry"} · " +
                    "class ${f.deviceClass?.let { "0x%06X".format(it) } ?: "unknown"} · " +
                    when (f.type) {
                        android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC -> "Classic"
                        android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL -> "Dual-mode"
                        android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE -> "BLE"
                        else -> "type unknown"
                    },
                fontSize = 11.sp, color = ConsoleMuted,
            )
            (if (expanded) a.reasons else a.reasons.take(2)).forEach { Text(it, fontSize = 11.sp, color = if (it.startsWith("-")) OkGreen else Color.Unspecified) }
            if (a.reasons.size > 2) androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide details" else "Show all ${a.reasons.size} reasons", fontSize = 12.sp)
            }
        }
    }
}
