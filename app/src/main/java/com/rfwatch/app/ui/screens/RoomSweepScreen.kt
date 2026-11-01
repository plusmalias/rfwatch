package com.rfwatch.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.WarnAmber
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.core.rules.Threat
import com.rfwatch.core.df.EmitterEstimate
import com.rfwatch.core.df.EstimateStatus
import java.util.Locale

/** Heuristic BLE/Wi-Fi review; passive radio scans cannot confirm that a device is a camera. */
@Composable
fun RoomSweepScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier) {
    val findings by vm.room.collectAsState()
    val estimates = remember { mutableStateMapOf<String, EmitterEstimate>() }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("ENVIRONMENT CHECK", fontSize = 10.sp, color = WarnAmber, letterSpacing = 1.3.sp)
        Text("Room sweep", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text(
            "Checks advertised BLE names and Wi-Fi identifiers for camera/IoT signatures. " +
                "Candidates are not proof; wired, powered-off, and non-broadcasting cameras are invisible to radio scans. " +
                "Walk slowly round the room for ~2 min (Wi-Fi scans are rate-limited), then tap Hunt on a finding and follow " +
                "hotter / colder to the object.",
            fontSize = 12.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(bottom = 8.dp),
        )
        if (findings.isEmpty()) {
            Text("No matching advertised BLE / Wi-Fi patterns yet. Keep monitoring on and give it ~2 minutes.", fontSize = 13.sp)
        }
        LazyColumn {
            items(findings, key = { "${it.key}:${it.reason}" }) { f ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = ConsolePanel), border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleOutline)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            f.reason.name.replace('_', ' '),
                            fontSize = 14.sp,
                            color = when (f.threat) {
                                Threat.PRIORITY -> DangerRed
                                Threat.NOTABLE -> WarnAmber
                                else -> Color.White
                            },
                        )
                        Text(f.detail, fontSize = 12.sp, color = Color(0xFF9CA3AF))
                        Text("${f.key} · ${f.rssi} dBm", fontSize = 11.sp, color = Color(0xFF6B7280))
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            // Hunt (hotter / colder) is what works inside one room; the path-loss Locate fit needs 100 m+ of movement.
                            Button(onClick = { vm.openDevice(f.key, com.rfwatch.app.ui.DeviceDest.HUNT) }) { Text("Hunt") }
                            androidx.compose.material3.TextButton(onClick = { vm.estimate(f.key) { estimates[f.key] = it } }) { Text("Locate (outdoors)") }
                        }
                        estimates[f.key]?.let { estimate ->
                            val result = when {
                                estimate.status == EstimateStatus.LOCATED && estimate.lat != null && estimate.lon != null -> {
                                    val error = estimate.ellipse?.semiMajorM?.let { " · ±${it.toInt()} m" } ?: ""
                                    "Estimated transmitter ${String.format(Locale.US, "%.5f, %.5f", estimate.lat, estimate.lon)}$error · ${estimate.sampleCount} samples"
                                }
                                estimate.status == EstimateStatus.MOBILE -> "Signal source appears mobile; fixed location suppressed."
                                else -> "Not enough movement yet: ${estimate.reason ?: "collect more GPS-tagged sightings"}."
                            }
                            Text(result, fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        }
                    }
                }
            }
        }
    }
}
