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
        Text("Room sweep", fontSize = 18.sp)
        Text(
            "Checks advertised BLE names and Wi-Fi identifiers for camera/IoT signatures. " +
                "Candidates are not proof; wired, powered-off, and non-broadcasting cameras are invisible to radio scans.",
            fontSize = 12.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(bottom = 8.dp),
        )
        if (findings.isEmpty()) {
            Text("No matching advertised BLE / Wi-Fi patterns yet. Keep scanning active.", fontSize = 13.sp)
        }
        LazyColumn {
            items(findings, key = { "${it.key}:${it.reason}" }) { f ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
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
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                            Text("${f.key} · ${f.rssi} dBm", fontSize = 11.sp, color = Color(0xFF6B7280))
                            Button(onClick = { vm.estimate(f.key) { estimates[f.key] = it } }) { Text("Locate") }
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
