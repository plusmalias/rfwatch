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
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.material3.Text
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.height
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.ConsolePanelRaised
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.core.id.DeviceKind
import com.rfwatch.core.cs.FollowerExclusion
import com.rfwatch.core.cs.FollowerScore
import java.util.Date
import java.util.Locale

/** Full device inventory in stable discovery order; the selected device stays pinned above live rows. */
@Composable
fun DevicesScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier, onRoom: () -> Unit = {}) {
    val devices by vm.devices.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf("") }
    var categoryFilter by remember { mutableStateOf(FILTER_ALL) }
    var sortMode by remember { mutableStateOf("Discovery") }
    val selected = devices.firstOrNull { it.id == selectedId }
    val visibleDevices = devices.filter { device ->
        val text = listOf(device.displayName, device.id, device.lastMac, device.brand, device.category, device.band, device.trackerType, device.model, device.effectiveKind().label)
            .filterNotNull().joinToString(" ").lowercase()
        val matchesQuery = query.isBlank() || text.contains(query.trim().lowercase())
        val matchesCategory = when (categoryFilter) {
            FILTER_ALL -> true
            FILTER_WIFI -> device.band == "WIFI"
            FILTER_BLE -> device.band == "BLE"
            else -> device.effectiveKind().name == categoryFilter
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
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SIGNAL INVENTORY", fontSize = 10.sp, color = ConsoleMuted, letterSpacing = 1.2.sp)
                Text("${visibleDevices.size} / ${devices.size} devices", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
            Button(onClick = onRoom) { Text("Room sweep") }
        }
        OutlinedTextField(
            value = query, onValueChange = { query = it }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            placeholder = { Text("Search name, brand, ID, category") },
        )
        // Category chips with live counts; empty categories are hidden except Axon / law.
        val kindCounts = devices.groupingBy { it.effectiveKind() }.eachCount()
        val chips = buildList {
            add(FILTER_ALL to "All ${devices.size}")
            add(DeviceKind.LAW_ENFORCEMENT.name to "${DeviceKind.LAW_ENFORCEMENT.label} ${kindCounts[DeviceKind.LAW_ENFORCEMENT] ?: 0}")
            DeviceKind.entries.filter { it != DeviceKind.LAW_ENFORCEMENT && (kindCounts[it] ?: 0) > 0 }
                .forEach { add(it.name to "${it.label} ${kindCounts[it]}") }
            add(FILTER_WIFI to "Wi-Fi ${devices.count { it.band == "WIFI" }}")
            add(FILTER_BLE to "BLE ${devices.count { it.band == "BLE" }}")
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            chips.forEach { (key, label) ->
                FilterChip(selected = categoryFilter == key, onClick = { categoryFilter = key }, label = { Text(label) })
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
    var showRawAdvertisement by remember(d.id) { mutableStateOf(false) }
    var followerScore by remember(d.id) { mutableStateOf<FollowerScore?>(null) }
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
            containerColor = if (selected) Color(0xFF102A2A) else ConsolePanel,
        ),
        border = BorderStroke(1.dp, if (selected) com.rfwatch.app.ui.theme.RadarCyan.copy(alpha = 0.7f) else ConsoleOutline),
    ) {
                    Column(Modifier.padding(10.dp)) {
                        Text((if (selected) "● SELECTED  /  " else "") + (d.displayName ?: d.id), fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                        Text(
                            listOfNotNull(
                                d.brand ?: if (d.band == "BLE") "Brand not advertised" else "Brand unknown",
                                d.model,
                                d.category ?: d.effectiveKind().label,
                            ).distinct().joinToString(" · "),
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
                            "~${formatRssiDistance(rssiDistanceEstimateMeters(d.lastRssi))} m (RSSI guess)" +
                                (if (d.seenCount > 0) " · heard ${d.seenCount}×" else "") +
                                (if (d.rssiMin != null && d.rssiMax != null) " · ${d.rssiMin}…${d.rssiMax} dBm" else "") +
                                listOfNotNull(d.wifiBand, d.wifiSecurity).joinToString("") { " · $it" },
                            fontSize = 10.sp, color = Color(0xFF9CA3AF),
                        )
                        if (selected) {
                            Text("Device ID: ${d.lastMac ?: d.id}", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                            val rawHex = d.radioDetails?.substringAfter("Raw advertisement hex: ")
                                ?.takeIf { it != d.radioDetails && it.isNotBlank() }
                            val detailText = d.radioDetails?.substringBefore(" · Raw advertisement hex:")
                            Text(detailText ?: "Detailed radio identifiers not available yet", fontSize = 10.sp, color = Color(0xFF9CA3AF))
                            if (rawHex != null) {
                                TextButton(onClick = { showRawAdvertisement = !showRawAdvertisement }) {
                                    Text(if (showRawAdvertisement) "Hide raw advertisement" else "Inspect raw advertisement")
                                }
                                if (showRawAdvertisement) {
                                    Text(
                                        "${rawHex.chunked(2).chunked(16).joinToString("\n") { it.joinToString(" ") }}\nRaw BLE advertising bytes · hex",
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 9.sp,
                                        color = ConsoleMuted,
                                    )
                                }
                            }
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { vm.setDeviceFlags(d.id, own = !d.isOwnDevice) }) {
                                    Text(if (d.isOwnDevice) "Unmark mine" else "Mark mine")
                                }
                                OutlinedButton(onClick = { vm.setDeviceFlags(d.id, trusted = !d.isAllowlisted) }) {
                                    Text(if (d.isAllowlisted) "Untrust" else "Trust")
                                }
                            }
                            Text("Your devices and trusted devices are excluded from tracker co-movement scoring.", fontSize = 9.sp, color = ConsoleMuted)
                            if (rssiHistory.isNotEmpty()) {
                                val minRssi = rssiHistory.minOrNull() ?: d.lastRssi
                                val maxRssi = rssiHistory.maxOrNull() ?: d.lastRssi
                                Text("Recent signal: $minRssi to $maxRssi dBm · ${rssiHistory.size} samples (last 60)", fontSize = 10.sp, color = Color(0xFF9CA3AF))
                                RssiSparkline(rssiHistory, deviceCategoryColor(d.category, d.classification))
                            }
                            if (isTrackerCandidate(d)) {
                                Button(onClick = { vm.analyzeFollower(d.id) { followerScore = it } }) {
                                    Text(if (followerScore == null) "Analyze co-movement" else "Recheck movement")
                                }
                                followerScore?.let { FollowerEvidence(it) }
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

private fun isTrackerCandidate(d: com.rfwatch.app.data.DeviceEntity): Boolean =
    d.trackerType != null || d.effectiveKind() == DeviceKind.TRACKER

@Composable
private fun FollowerEvidence(score: FollowerScore) {
    val status = when {
        score.exclusion == FollowerExclusion.OWN_DEVICE -> "Excluded: marked as your device."
        score.exclusion == FollowerExclusion.ALLOWLISTED -> "Excluded: marked as trusted."
        score.exclusion == FollowerExclusion.STATIONARY_INFRASTRUCTURE -> "Excluded: classified as stationary infrastructure."
        score.isFollower -> "Possible co-movement pattern. This is not proof that a tracker is following you."
        score.distinctLocations == 0 -> "No GPS-tagged sightings yet. Keep location enabled during a walk or drive."
        else -> "The stored sightings do not meet the co-movement evidence thresholds. This cannot rule out tracking."
    }
    Column(Modifier.fillMaxWidth().padding(top = 4.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(status, fontSize = 11.sp, color = if (score.isFollower) com.rfwatch.app.ui.theme.WarnAmber else ConsoleMuted)
        Text(
            "Evidence: ${score.distinctLocations} separated sightings · ${score.maxSeparationM.toInt()} m spread · " +
                "${score.userTravelM.toInt()} m phone route · ${score.spanMs / 60_000} min · " +
                "pattern score ${(score.score * 100).toInt()}/100 (heuristic)",
            fontSize = 9.sp,
            color = ConsoleMuted,
        )
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

private const val FILTER_ALL = "ALL"
private const val FILTER_WIFI = "WIFI"
private const val FILTER_BLE = "BLE"

/** Stored kind, or one inferred from the legacy category text for rows saved before v4. */
internal fun com.rfwatch.app.data.DeviceEntity.effectiveKind(): DeviceKind {
    kind?.let { k -> runCatching { DeviceKind.valueOf(k) }.getOrNull()?.let { return it } }
    val c = category.orEmpty().lowercase()
    return when {
        trackerType != null || "tracker" in c -> DeviceKind.TRACKER
        "axon" in c || "law" in c -> DeviceKind.LAW_ENFORCEMENT
        "camera" in c || "iot" in c -> DeviceKind.CAMERA_IOT
        "fleet" in c || "vehicle" in c -> DeviceKind.FLEET
        band == "WIFI" -> DeviceKind.NETWORK
        else -> DeviceKind.UNKNOWN
    }
}
