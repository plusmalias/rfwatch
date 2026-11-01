package com.rfwatch.app.ui.screens

import com.rfwatch.app.ui.toRowInfo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.border
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
import com.rfwatch.app.ui.BandFilter
import com.rfwatch.app.ui.DeviceFilter
import com.rfwatch.app.ui.DeviceSort
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.StatusFilter
import com.rfwatch.app.ui.TimeWindow
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.ConsolePanelRaised
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.core.id.DeviceKind
import com.rfwatch.core.cs.FollowerExclusion
import com.rfwatch.core.cs.FollowerScore
import java.util.Date
import java.util.Locale

/**
 * Full device inventory. Filtering/sorting runs in the ViewModel off the main thread; the
 * selected device stays pinned above the live rows even when filters would hide it.
 */
@Composable
fun DevicesScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier, onRoom: () -> Unit = {}) {
    val devices by vm.devices.collectAsState()
    val view by vm.deviceView.collectAsState()
    val filter by vm.deviceFilter.collectAsState()
    var selectedId by remember { mutableStateOf<String?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    var showTypeFilters by remember { mutableStateOf(false) }
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    val nav by vm.deviceNav.collectAsState()
    LaunchedEffect(nav) {
        val n = nav ?: return@LaunchedEffect
        if (n.dest != com.rfwatch.app.ui.DeviceDest.DETAILS) return@LaunchedEffect
        selectedId = n.deviceId
        // Consume after scrolling: clearing the request re-keys (cancels) this effect.
        try { androidx.compose.runtime.withFrameNanos { }; listState.scrollToItem(0) } // selected card is pinned first
        finally { vm.consumeNav(n) }
    }
    val selected = selectedId?.let { id -> devices.firstOrNull { it.id == id } }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("SIGNAL INVENTORY", fontSize = 10.sp, color = ConsoleMuted, letterSpacing = 1.2.sp, maxLines = 1)
                Text("${view.visible.size} / ${view.total} devices", fontSize = 17.sp, maxLines = 1, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
            if (!filter.isDefault) {
                TextButton(onClick = { vm.updateDeviceFilter { DeviceFilter(sort = it.sort) } }) { Text("Clear") }
            }
            Button(onClick = onRoom, modifier = Modifier.padding(start = 6.dp)) { Text("Room sweep") }
        }
        OutlinedTextField(
            value = filter.query, onValueChange = { q -> vm.updateDeviceFilter { it.copy(query = q) } }, singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            placeholder = { Text("Search name, brand, model, ID") },
            trailingIcon = if (filter.query.isNotEmpty()) {
                { TextButton(onClick = { vm.updateDeviceFilter { it.copy(query = "") } }) { Text("✕") } }
            } else null,
        )
        // Row 1: time window + status filters (status chips combine with AND).
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box {
                FilterChip(selected = filter.sort != DeviceSort.DISCOVERY, onClick = { sortMenu = true }, label = { Text("⇅ ${filter.sort.label}") })
                androidx.compose.material3.DropdownMenu(expanded = sortMenu, onDismissRequest = { sortMenu = false }) {
                    DeviceSort.entries.forEach { option ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { Text((if (option == filter.sort) "● " else "") + option.label) },
                            onClick = { vm.updateDeviceFilter { it.copy(sort = option) }; sortMenu = false },
                        )
                    }
                }
            }
            Text("|", color = ConsoleMuted)
            TimeWindow.entries.forEach { w ->
                FilterChip(selected = filter.window == w, onClick = { vm.updateDeviceFilter { it.copy(window = w) } }, label = { Text(w.label) })
            }
            Text("|", color = ConsoleMuted)
            StatusFilter.entries.forEach { st ->
                val count = view.statusCounts[st] ?: 0
                val on = st in filter.status
                FilterChip(
                    selected = on,
                    onClick = { vm.updateDeviceFilter { it.copy(status = if (on) it.status - st else it.status + st) } },
                    label = { Text(if (st == StatusFilter.HIDE_MINE) st.label else "${st.label} $count",
                        color = if (st == StatusFilter.THREATS && count > 0 && !on) com.rfwatch.app.ui.theme.WarnAmber else Color.Unspecified) },
                )
            }
        }
        val extraFilterCount = filter.kinds.size + if (filter.band != null) 1 else 0
        TextButton(
            onClick = { showTypeFilters = !showTypeFilters },
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 0.dp, vertical = 0.dp),
        ) {
            Text("${if (showTypeFilters) "⌄" else "›"} Device type & band${if (extraFilterCount > 0) " · $extraFilterCount active" else ""}", fontSize = 11.sp, color = ConsoleMuted)
        }
        // Device kinds and radio band are available on demand, leaving more room for results.
        if (showTypeFilters || extraFilterCount > 0) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
            FilterChip(selected = filter.kinds.isEmpty(), onClick = { vm.updateDeviceFilter { it.copy(kinds = emptySet()) } },
                label = { Text("All kinds") })
            DeviceKind.entries
                .filter { it == DeviceKind.LAW_ENFORCEMENT || (view.kindCounts[it] ?: 0) > 0 || it in filter.kinds }
                .sortedWith(compareBy<DeviceKind> { it != DeviceKind.LAW_ENFORCEMENT }.thenByDescending { view.kindCounts[it] ?: 0 })
                .forEach { k ->
                    val on = k in filter.kinds
                    FilterChip(selected = on,
                        onClick = { vm.updateDeviceFilter { it.copy(kinds = if (on) it.kinds - k else it.kinds + k) } },
                        label = { Text("${k.label} ${view.kindCounts[k] ?: 0}") })
                }
            Text("|", color = ConsoleMuted)
            BandFilter.entries.forEach { b ->
                val on = filter.band == b
                FilterChip(selected = on, onClick = { vm.updateDeviceFilter { it.copy(band = if (on) null else b) } },
                    label = { Text("${b.label} ${view.bandCounts[b] ?: 0}") })
            }
        }
        LazyColumn(Modifier.fillMaxWidth().weight(1f).padding(top = 2.dp), state = listState, verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (selected != null) {
                item(key = "selected-${selected.id}") {
                    DeviceCard(selected, selected = true, vm = vm) { selectedId = null }
                }
            }
            val now = System.currentTimeMillis()
            items(view.visible.filterNot { it.id == selectedId }, key = { it.id }) { d ->
                com.rfwatch.app.ui.DeviceRowCompact(d.toRowInfo(now)) { selectedId = d.id }
            }
            if (view.visible.isEmpty()) {
                item(key = "empty") {
                    Column(Modifier.fillMaxWidth().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(if (view.total == 0) "No devices heard yet. Start scanning on Radar." else "No devices match these filters.",
                            fontSize = 13.sp, color = ConsoleMuted)
                        if (view.total > 0 && !filter.isDefault) {
                            TextButton(onClick = { vm.updateDeviceFilter { DeviceFilter(sort = it.sort) } }) { Text("Clear filters") }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DeviceCard(d: com.rfwatch.app.data.DeviceEntity, selected: Boolean, vm: RfWatchViewModel, onClick: () -> Unit) {
    var rssiHistory by remember(d.id) { mutableStateOf(emptyList<Int>()) }
    var showRawAdvertisement by remember(d.id) { mutableStateOf(false) }
    var sameHints by remember(d.id) { mutableStateOf<List<Pair<com.rfwatch.core.cs.SameDeviceHint, com.rfwatch.app.data.DeviceEntity>>>(emptyList()) }
    LaunchedEffect(d.id, selected) { sameHints = if (selected) vm.sameDeviceHints(d.id) else emptyList() }
    var followerScore by remember(d.id) { mutableStateOf<FollowerScore?>(null) }
    var renaming by remember(d.id) { mutableStateOf(false) }
    if (renaming) {
        var text by remember(d.id) { mutableStateOf(d.nickname ?: "") }
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { renaming = false },
            title = { Text("Name this device") },
            text = {
                Column {
                    Text("Only stored on this phone, inside the encrypted database.", fontSize = 12.sp, color = ConsoleMuted)
                    OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true,
                        placeholder = { Text("e.g. My AirPods") }, modifier = Modifier.padding(top = 8.dp))
                }
            },
            confirmButton = { TextButton(onClick = { vm.setNickname(d.id, text); renaming = false }) { Text("Save") } },
            dismissButton = {
                Row {
                    if (d.nickname != null) TextButton(onClick = { vm.setNickname(d.id, null); renaming = false }) { Text("Remove name") }
                    TextButton(onClick = { renaming = false }) { Text("Cancel") }
                }
            },
        )
    }
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
                    Column(Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                com.rfwatch.app.ui.friendlyName(d.nickname, d.displayName, d.brand, d.effectiveKind(), d.id),
                                fontSize = 16.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, modifier = Modifier.weight(1f),
                            )
                            if (selected) {
                                TextButton(onClick = { renaming = true }) { Text(if (d.nickname == null) "Rename" else "Edit name") }
                                TextButton(onClick = onClick) { Text("Close") }
                            }
                        }
                        if (d.nickname != null && d.displayName != null) Text("Advertises as \"${d.displayName}\"", fontSize = 11.sp, color = ConsoleMuted)
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
                            if (sameHints.isNotEmpty()) SameDeviceSection(sameHints) { vm.openDevice(it, com.rfwatch.app.ui.DeviceDest.DETAILS) }
                            com.rfwatch.app.ui.DeviceLinks(vm, d.id, here = com.rfwatch.app.ui.DeviceDest.DETAILS)
                            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                OutlinedButton(onClick = { vm.setDeviceFlags(d.id, own = !d.isOwnDevice) }) {
                                    Text(if (d.isOwnDevice) "Unmark mine" else "Mark mine")
                                }
                                OutlinedButton(onClick = { vm.setDeviceFlags(d.id, trusted = !d.isAllowlisted) }) {
                                    Text(if (d.isAllowlisted) "Untrust" else "Trust")
                                }
                            }
                            val exportContext = androidx.compose.ui.platform.LocalContext.current
                            Button(onClick = { vm.exportEvidence(exportContext, d.id) { exportContext.startActivity(it) } }) {
                                Text("Export evidence report")
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

/** Stored kind, or one inferred from the legacy category text for rows saved before v4. */
internal fun com.rfwatch.app.data.DeviceEntity.effectiveKind(): DeviceKind {
    kind?.let { k -> runCatching { DeviceKind.valueOf(k) }.getOrNull()?.let { return it } }
    val c = category.orEmpty().lowercase()
    return when {
        trackerType != null || "tracker" in c -> DeviceKind.TRACKER
        "axon" in c || "law" in c -> DeviceKind.LAW_ENFORCEMENT
        "camera" in c || "iot" in c -> DeviceKind.CAMERA_IOT
        "emergency services" in c -> DeviceKind.EMERGENCY
        "hacking tool" in c -> DeviceKind.ATTACK_TOOL
        "fleet" in c || "vehicle" in c -> DeviceKind.FLEET
        band == "WIFI" -> DeviceKind.NETWORK
        else -> DeviceKind.UNKNOWN
    }
}

/** "Probably the same device" hints: other rows that look like this device under another address. Display only. */
@Composable
private fun SameDeviceSection(
    hints: List<Pair<com.rfwatch.core.cs.SameDeviceHint, com.rfwatch.app.data.DeviceEntity>>,
    open: (String) -> Unit,
) {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 4.dp)
            .border(1.dp, com.rfwatch.app.ui.theme.RadarCyan.copy(alpha = 0.35f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .padding(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Text("MAY BE THE SAME DEVICE", fontSize = 10.sp, color = com.rfwatch.app.ui.theme.RadarCyan, letterSpacing = 1.sp)
        hints.sortedBy { it.first.strength }.forEach { (h, other) ->
            Column(Modifier.fillMaxWidth().clickable { open(other.id) }) {
                Text(
                    (if (h.strength == com.rfwatch.core.cs.HintStrength.PROBABLY) "Probably: " else "Possibly: ") +
                        com.rfwatch.app.ui.friendlyName(other.nickname, other.displayName, other.brand, other.effectiveKind(), other.id) + "  ›",
                    fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                    color = if (h.strength == com.rfwatch.core.cs.HintStrength.PROBABLY) Color.White else Color(0xFFCBD5E1),
                )
                Text(h.reason, fontSize = 10.sp, color = ConsoleMuted)
            }
        }
        Text("A hint, not proof — records are kept separate. Tap one to open it.", fontSize = 9.sp, color = ConsoleMuted)
    }
}
