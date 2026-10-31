package com.rfwatch.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.rfwatch.app.ui.RfWatchViewModel
import java.util.Date
import java.util.Locale

/** Full device inventory in stable discovery order; the selected device stays pinned above live rows. */
@Composable
fun DevicesScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier, onRoom: () -> Unit = {}) {
    val devices by vm.devices.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf("All") }
    var sortMode by remember { mutableStateOf("Discovery") }
    val selected = devices.firstOrNull { it.id == selectedId }
    val visibleDevices = devices.filter { device ->
        val text = listOf(device.displayName, device.id, device.lastMac, device.brand, device.category, device.band, device.trackerType)
            .filterNotNull().joinToString(" ").lowercase()
        val matchesQuery = query.isBlank() || text.contains(query.trim().lowercase())
        val category = device.category.orEmpty().lowercase()
        val matchesCategory = when (categoryFilter) {
            "Wi-Fi" -> device.band == "WIFI"
            "BLE" -> device.band == "BLE"
            "Tracker" -> "tracker" in category || device.trackerType != null
            "Camera" -> "camera" in category || "iot" in category
            "Axon / law" -> "axon" in category || "law" in category
            else -> true
        }
        matchesQuery && matchesCategory
    }.let { filtered ->
        when (sortMode) {
            "Strongest", "Approx. closest" -> filtered.sortedByDescending { it.lastRssi }
            else -> filtered
        }
    }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Devices (${visibleDevices.size}/${devices.size})", fontSize = 18.sp)
            Button(onClick = onRoom) { Text("Room sweep") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            placeholder = { Text("Search name, brand, ID, category") },
        )
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("All", "Wi-Fi", "BLE", "Tracker", "Camera", "Axon / law").forEach { option ->
                FilterChip(selected = categoryFilter == option, onClick = { categoryFilter = option }, label = { Text(option) })
            }
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("Discovery", "Strongest", "Approx. closest").forEach { option ->
                FilterChip(selected = sortMode == option, onClick = { sortMode = option }, label = { Text(option) })
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (selected != null) {
                item(key = "selected-${selected.id}") {
                    DeviceCard(selected, selected = true, vm = vm) { selectedId = null }
                }
            }
            items(visibleDevices.filterNot { it.id == selectedId }, key = { it.id }) { d ->
                DeviceCard(d, selected = false, vm = vm) { selectedId = d.id }
            }
        }
    }
}

@Composable
private fun DeviceCard(d: com.rfwatch.app.data.DeviceEntity, selected: Boolean, vm: RfWatchViewModel, onClick: () -> Unit) {
    var rssiHistory by remember(d.id) { mutableStateOf(emptyList<Int>()) }
    LaunchedEffect(d.id, selected) {
        if (selected) {
            while (isActive) {
                rssiHistory = vm.recentObservations(d.id).map { it.rssi }.reversed()
                delay(2_000)
            }
        } else {
            rssiHistory = emptyList()
        }
    }
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) androidx.compose.material3.MaterialTheme.colorScheme.secondaryContainer else androidx.compose.material3.MaterialTheme.colorScheme.surfaceVariant,
        ),
    ) {
                    Column(Modifier.padding(10.dp)) {
                        Text((if (selected) "Selected · " else "") + (d.displayName ?: d.id), fontSize = 14.sp)
                        Text(
                            "${d.brand ?: "Brand unknown"} · ${d.category ?: if (d.band == "WIFI") "Wi-Fi device" else "Bluetooth device"}",
                            fontSize = 12.sp, color = deviceCategoryColor(d.category, d.classification),
                        )
                        Text(
                            "${d.band} · ${d.classification}" +
                                (d.trackerType?.let { " · $it" } ?: "") +
                                " · ${d.lastRssi} dBm" +
                                (if (d.isOwnDevice) " · own" else "") +
                                (if (d.isAllowlisted) " · allowlisted" else ""),
                            fontSize = 12.sp, color = Color(0xFF9CA3AF),
                        )
                        Text(
                            "RSSI-only distance: ~${formatRssiDistance(rssiDistanceEstimateMeters(d.lastRssi))} m · very rough; not a measured range",
                            fontSize = 10.sp, color = Color(0xFF9CA3AF),
                        )
                        if (selected) {
                            Text("Device ID: ${d.lastMac ?: d.id}", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                            Text(d.radioDetails ?: "Detailed radio identifiers not available yet", fontSize = 10.sp, color = Color(0xFF9CA3AF))
                            if (rssiHistory.isNotEmpty()) {
                                val minRssi = rssiHistory.minOrNull() ?: d.lastRssi
                                val maxRssi = rssiHistory.maxOrNull() ?: d.lastRssi
                                Text("Recent signal: $minRssi to $maxRssi dBm · ${rssiHistory.size} samples (last 60)", fontSize = 10.sp, color = Color(0xFF9CA3AF))
                                RssiSparkline(rssiHistory, deviceCategoryColor(d.category, d.classification))
                            }
                        } else {
                            Text("Tap for radio identifiers and scan details", fontSize = 10.sp, color = Color(0xFF82939B))
                        }
                        val lat = d.observedLat
                        val lon = d.observedLon
                        if (lat != null && lon != null) {
                            val accuracy = d.observedAccuracyM?.let { " · ±${it.toInt()} m phone GPS" } ?: " · phone GPS"
                            val time = d.observedAtMs?.let {
                                " · ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(Date(it))}"
                            } ?: ""
                            Text(
                                "Seen near ${String.format(Locale.US, "%.5f, %.5f", lat, lon)}$accuracy$time",
                                fontSize = 11.sp, color = Color(0xFF9CA3AF),
                            )
                        } else {
                            Text("Seen near: GPS fix unavailable", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        }
                    }
    }
}

@Composable
private fun RssiSparkline(samples: List<Int>, color: Color) {
    Canvas(Modifier.fillMaxWidth().height(34.dp).padding(top = 3.dp, bottom = 3.dp)) {
        if (samples.size < 2) return@Canvas
        val min = (samples.minOrNull() ?: -100).toFloat()
        val max = (samples.maxOrNull() ?: -30).toFloat()
        val span = (max - min).coerceAtLeast(1f)
        val path = Path()
        samples.forEachIndexed { index, value ->
            val x = size.width * index / (samples.size - 1)
            val y = size.height - ((value - min) / span * size.height)
            if (index == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color.copy(alpha = 0.25f), style = Stroke(width = 5f))
        drawPath(path, color, style = Stroke(width = 2f))
    }
}
