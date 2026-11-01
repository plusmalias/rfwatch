package com.rfwatch.app.ui.screens

import com.rfwatch.app.ui.toRowInfo
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.horizontalScroll
import com.rfwatch.app.ui.theme.OkGreen
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.repo.LiveContact
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.RadarCyan
import com.rfwatch.app.ui.theme.RadarPink
import com.rfwatch.app.ui.theme.WarnAmber
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.ConsolePanelRaised
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.app.ui.theme.SignalBlue
import com.rfwatch.app.data.DeviceEntity
import com.rfwatch.app.repo.ScanSessionStatus
import com.rfwatch.core.df.EstimateStatus
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import java.util.Locale

/**
 * Radar v2 (step 5). Heading-up: the display rotates with the device, so the top of the
 * scope is whatever direction the phone is pointing. Contacts with a confident estimate are
 * drawn at their true relative bearing and scaled distance with an uncertainty wedge;
 * contacts without one sit on the grey outer ring labelled "bearing unknown"; mobile
 * emitters show an approach/recede arrow. Below the scope, each contact lists its actions.
 */
@Composable
fun RadarScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier) {
    val heading by vm.heading.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val devices by vm.devices.collectAsState()
    val law by vm.lawEncounter.collectAsState()
    // Keep discovery order stable. RSSI fluctuates per packet, so sorting on it makes targets jump.
    val list = contacts.values.toList()
    var selectedContactId by remember { mutableStateOf<String?>(null) }
    var chartContactId by remember { mutableStateOf<String?>(null) }
    var showAllNearby by remember { mutableStateOf(false) }
    var sessionDialog by remember { mutableStateOf(false) }
    // Contacts are kept for the whole session; "now" means heard within the last minute (Wi-Fi 2.5 min).
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(5_000); nowMs = System.currentTimeMillis() } }
    val trendTracker = remember { TrendTracker() }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences("rfwatch_settings", android.content.Context.MODE_PRIVATE) }
    var radarFilter by remember { mutableStateOf(prefs.getString("radar_filter", RADAR_FILTER_ALL) ?: RADAR_FILTER_ALL) }
    val liveNow = list.filter { it.isLiveNow(maxOf(nowMs, System.currentTimeMillis() - 1_000)) }
    val filtered = liveNow.filter { it.matchesRadarFilter(radarFilter) }
    val trends = remember(contacts, nowMs) { trendTracker.update(liveNow, System.currentTimeMillis()) }
    val positions by vm.positions.collectAsState()
    val drones by vm.drones.collectAsState()
    val followers by vm.followers.collectAsState()
    val userLocation by vm.userLocation.collectAsState()
    // Live contact, or (when opened from another screen) one rebuilt from the stored device.
    val selectedContact = selectedContactId?.let { id -> contacts[id] ?: devices.firstOrNull { it.id == id }?.let { vm.contactFor(id) } }
    val radarListState = androidx.compose.foundation.lazy.rememberLazyListState()
    val nav by vm.deviceNav.collectAsState()
    LaunchedEffect(nav) {
        val n = nav ?: return@LaunchedEffect
        if (n.dest != com.rfwatch.app.ui.DeviceDest.LOCATE) return@LaunchedEffect
        selectedContactId = n.deviceId
        chartContactId = n.deviceId
        // Bring the Locate panel into view: walk down until its keyed item is laid out.
        // Consume only afterwards — clearing the request re-keys (cancels) this effect.
        try {
            androidx.compose.runtime.withFrameNanos { }
            radarListState.scrollToItem(0)
            for (step in 0 until 40) {
                val hit = radarListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "locate-${n.deviceId}" }
                if (hit != null) { radarListState.animateScrollToItem(hit.index); break }
                radarListState.scrollToItem(radarListState.firstVisibleItemIndex + 1)
                androidx.compose.runtime.withFrameNanos { }
            }
        } finally {
            vm.consumeNav(n)
        }
    }
    val latestIds by androidx.compose.runtime.rememberUpdatedState(filtered.map { it.id })
    LaunchedEffect(Unit) {
        if (com.rfwatch.app.BuildConfig.DEBUG) { kotlinx.coroutines.delay(10_000); vm.logRadarReport(latestIds) }
    }
    LaunchedEffect(Unit) {
        while (true) {
            vm.refreshPositions(latestIds)
            kotlinx.coroutines.delay(5_000)
        }
    }
    val facing = heading.deg ?: 0.0
    val priorityCount = list.count { it.classification == "PRIORITY" || it.classification == "TRACKER" }
    val session by vm.scanSession.collectAsState()
    var confirmClearSession by remember { mutableStateOf(false) }

    val storedById = devices.associateBy { it.id }
    val threatIds = liveNow.filter { it.isThreatNow(storedById[it.id]) }.map { it.id }.toSet()
    Box(modifier.fillMaxSize()) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        state = radarListState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 12.dp, bottom = if (selectedContact != null) 190.dp else 12.dp),
    ) {
        item(key = "radar-header") {
            // Same rule as the Devices "Threats" chip, so the two screens never disagree.
            val now = System.currentTimeMillis()
            val toCheck = devices.count { now - it.lastSeenMs < 2 * 60_000L && com.rfwatch.app.ui.isThreat(it, it.effectiveKind()) }
            val liveFilter = com.rfwatch.app.ui.DeviceFilter(window = com.rfwatch.app.ui.TimeWindow.LIVE, status = setOf(com.rfwatch.app.ui.StatusFilter.THREATS))
            val health by vm.scanHealth.collectAsState()
            val ble by vm.bleScanState.collectAsState()
            val located = liveNow.count { positions[it.id]?.directional == true || it.estimate?.status == EstimateStatus.LOCATED }
            val trackersNow = liveNow.count { it.kind == com.rfwatch.core.id.DeviceKind.TRACKER || it.trackerType != null }
            RadarConsole(
                toCheck = toCheck, nearby = liveNow.size, located = located, trackers = trackersNow,
                health = health, bleState = ble, gpsAccuracyM = userLocation?.accuracyM, nowMs = maxOf(nowMs, health.updatedMs),
                session = session, onRec = { sessionDialog = true },
                onToCheck = { vm.openDevicesWith(liveFilter) },
                onTrackers = { vm.openDevicesWith(com.rfwatch.app.ui.DeviceFilter(kinds = setOf(com.rfwatch.core.id.DeviceKind.TRACKER), window = com.rfwatch.app.ui.TimeWindow.LIVE)) },
            )
        }
        item(key = "status-strip") {
            val home by vm.homeStatus.collectAsState()
            val activeLaw = law?.takeIf { it.active }
            val trackers = liveNow.count { it.kind == com.rfwatch.core.id.DeviceKind.TRACKER || it.trackerType != null }
            val recentDrones = drones.values.count { System.currentTimeMillis() - it.lastSeenMs < 5 * 60_000L }
            val ble by vm.bleScanState.collectAsState()
            Row(Modifier.fillMaxWidth().horizontalScroll(androidx.compose.foundation.rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                val scope = androidx.compose.runtime.rememberCoroutineScope()
                fun open(kind: com.rfwatch.core.id.DeviceKind, window: com.rfwatch.app.ui.TimeWindow) =
                    vm.openDevicesWith(com.rfwatch.app.ui.DeviceFilter(kinds = setOf(kind), window = window))
                StatusPill(if (activeLaw != null) "POLICE ${activeLaw.signatures.size}" else "No police", if (activeLaw != null) DangerRed else OkGreen) {
                    open(com.rfwatch.core.id.DeviceKind.LAW_ENFORCEMENT, com.rfwatch.app.ui.TimeWindow.TODAY)
                }
                StatusPill(if (followers.isNotEmpty()) "FOLLOWING ${followers.size}" else "No followers", if (followers.isNotEmpty()) WarnAmber else OkGreen) {
                    // Followers live on Radar's own card, just below.
                    if (followers.isNotEmpty()) scope.launch {
                        radarListState.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "followers" }?.let { radarListState.animateScrollToItem(it.index) }
                            ?: radarListState.animateScrollToItem(3)
                    }
                }
                StatusPill(if (recentDrones > 0) "DRONES $recentDrones" else "No drones", if (recentDrones > 0) Color(0xFFE040FB) else OkGreen) {
                    open(com.rfwatch.core.id.DeviceKind.DRONE, com.rfwatch.app.ui.TimeWindow.TODAY)
                }
                StatusPill(
                    when { !home.isSet -> "Home: not set"; !home.mature -> "Home: learning ${home.visitsLearned}/3"; home.inside -> "Home: watching"; else -> "Away" },
                    if (home.isSet) RadarCyan else ConsoleMuted,
                )
            }
        }
        law?.let { encounter ->
            if (encounter.active || System.currentTimeMillis() - encounter.lastSeenMs < LAW_BANNER_KEEP_MS) {
                item(key = "law-banner") { LawBanner(encounter) }
            }
        }
        if (followers.isNotEmpty()) {
            item(key = "followers") {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = WarnAmber.copy(alpha = 0.10f)),
                    border = BorderStroke(1.dp, WarnAmber.copy(alpha = 0.6f)),
                ) {
                    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("TRAVELLING WITH YOU (${followers.size})", fontSize = 12.sp, color = WarnAmber, letterSpacing = 1.sp)
                        followers.values.sortedByDescending { it.score }.forEach { f ->
                            val c = contacts[f.deviceId]
                            Text(
                                "• ${c?.label ?: f.deviceId} · ${c?.kind?.label ?: ""} — ${f.distinctLocations} places, " +
                                    "${"%.1f".format(f.maxSeparationM / 1000)} km apart, ${f.spanMs / 60_000} min",
                                fontSize = 12.sp,
                                modifier = Modifier.clickable { selectedContactId = f.deviceId; chartContactId = f.deviceId },
                            )
                        }
                        Text("Heard at several separate places while you moved. Your own phone/watch/earbuds will show here — mark them Mine in Devices.", fontSize = 10.sp, color = ConsoleMuted)
                    }
                }
            }
        }
        item(key = "radar-filter") { RadarFilterChips(liveNow, radarFilter) { radarFilter = it; prefs.edit().putString("radar_filter", it).apply() } }
        item(key = "radar-scope") {
            LocationScopePanel(filtered, positions, userLocation, selectedContactId, drones.values, trends, threatIds) {
                selectedContactId = if (selectedContactId == it) null else it
            }
        }
        val chartContact = chartContactId?.let { id -> contacts[id] ?: devices.firstOrNull { it.id == id }?.let { vm.contactFor(id) } }
        chartContact?.let { c ->
            item(key = "locate-${c.id}") { DeviceLocateChart(vm, c, userLocation) { chartContactId = null } }
        }
        item(key = "live-devices-heading") {
            val label = if (radarFilter == RADAR_FILTER_ALL) null
                else com.rfwatch.core.id.DeviceKind.entries.firstOrNull { it.name == radarFilter }?.label ?: radarFilter
            NearbyHeading(filtered.size, label) { radarFilter = RADAR_FILTER_ALL; prefs.edit().putString("radar_filter", RADAR_FILTER_ALL).apply() }
        }
        if (filtered.isEmpty()) {
            item(key = "no-live-devices") { EmptyConsoleNotice("Nothing heard in the last minute", "BLE and Wi-Fi detections appear here as the monitors receive them.") }
        }
        val nearby = sortNearby(filtered, storedById)
        items(if (showAllNearby) nearby else nearby.take(NEARBY_ROWS), key = { "live-${it.id}" }) { c ->
            NearbyRow(c, storedById[c.id], trends[c.id] ?: com.rfwatch.core.df.RssiTrend.UNKNOWN, selectedContactId == c.id) {
                selectedContactId = if (selectedContactId == c.id) null else c.id
            }
        }
        if (nearby.size > NEARBY_ROWS) item(key = "nearby-more") {
            TextButton(onClick = { showAllNearby = !showAllNearby }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showAllNearby) "Show top $NEARBY_ROWS" else "Show all ${nearby.size} nearby", fontSize = 12.sp)
            }
        }
        item(key = "inventory-link") {
            // The full saved inventory lives on the Devices tab (search, filters, details).
            TextButton(onClick = { vm.openDevicesWith(com.rfwatch.app.ui.DeviceFilter()) }, modifier = Modifier.fillMaxWidth()) {
                Text("All ${devices.size} saved devices → Devices", fontSize = 13.sp)
            }
        }
    }

    // Pop-up card for the selected device, over the bottom of the screen.
    selectedContact?.let { c ->
        Box(Modifier.align(Alignment.BottomCenter).padding(10.dp)) {
            SelectedDeviceCard(
                contact = c, stored = storedById[c.id], trend = trends[c.id] ?: com.rfwatch.core.df.RssiTrend.UNKNOWN,
                recent = trendTracker.history(c.id),
                positioned = positions[c.id]?.directional == true || c.estimate?.status == EstimateStatus.LOCATED,
                chartOpen = chartContactId == c.id,
                onHunt = { vm.openDevice(c.id, com.rfwatch.app.ui.DeviceDest.HUNT) },
                onChart = { chartContactId = if (chartContactId == c.id) null else c.id },
                onDetails = { vm.openDevice(c.id, com.rfwatch.app.ui.DeviceDest.DETAILS) },
                onClose = { selectedContactId = null },
            )
        }
    }
    }

    if (sessionDialog) {
        SessionDialog(
            status = session, onStart = vm::startScanSession, onSave = { vm.saveScanSession() },
            onClear = { confirmClearSession = true }, onDismiss = { sessionDialog = false },
        )
    }

    if (confirmClearSession) {
        AlertDialog(
            onDismissRequest = { confirmClearSession = false },
            title = { Text("Clear this scan session?") },
            text = {
                Text("This deletes observations tagged to the current session. Your device inventory and observations from other sessions stay saved.")
            },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearScanSession()
                    confirmClearSession = false
                }) { Text("Clear session") }
            },
            dismissButton = { TextButton(onClick = { confirmClearSession = false }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun StatusPill(text: String, color: Color, onClick: (() -> Unit)? = null) {
    androidx.compose.material3.Surface(
        modifier = if (onClick != null) Modifier.clip(CircleShape).clickable(onClick = onClick) else Modifier,
        color = color.copy(alpha = 0.12f), shape = CircleShape, border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) { Text(text, Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 11.sp, color = color, letterSpacing = 0.6.sp) }
}

@Composable
private fun EmptyConsoleNotice(title: String, detail: String) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ConsolePanel),
        border = BorderStroke(1.dp, ConsoleOutline),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            Text(detail, fontSize = 11.sp, color = ConsoleMuted)
        }
    }
}

@Composable
private fun InventoryRow(device: DeviceEntity) {
    val tint = deviceCategoryColor(device.category, device.classification)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ConsolePanel),
        border = BorderStroke(1.dp, ConsoleOutline),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 10.dp).size(9.dp).clip(CircleShape).background(tint))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(device.displayName ?: device.lastMac ?: device.id, fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, maxLines = 1)
                Text("${device.brand ?: "Brand unknown"} · ${device.category ?: if (device.band == "WIFI") "Wi-Fi device" else "Bluetooth device"}", fontSize = 11.sp, color = tint, maxLines = 1)
                Text("${device.band} · ${device.lastRssi} dBm · ~${formatRssiDistance(rssiDistanceEstimateMeters(device.lastRssi))} m RSSI estimate", fontSize = 10.sp, color = ConsoleMuted)
            }
        }
    }
}

@Composable
private fun LegendDot(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        androidx.compose.foundation.Canvas(Modifier.size(7.dp)) {
            drawCircle(color)
        }
        Text(label, fontSize = 9.sp, color = Color(0xFF9BB0B8), maxLines = 1)
    }
}

private const val LAW_BANNER_KEEP_MS = 10 * 60_000L
private const val NEARBY_ROWS = 8

@Composable
private fun LawBanner(encounter: com.rfwatch.core.alerts.LawEncounter) {
    val now = System.currentTimeMillis()
    val color = if (encounter.active) DangerRed else WarnAmber
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.12f)),
        border = BorderStroke(1.dp, color.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(
                if (encounter.active) "POSSIBLE POLICE EQUIPMENT NEARBY"
                else "POLICE EQUIPMENT LAST HEARD ${((now - encounter.lastSeenMs) / 60_000).coerceAtLeast(0)} MIN AGO",
                fontSize = 12.sp, color = color, letterSpacing = 1.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
            )
            encounter.signatures.take(6).forEach {
                Text(
                    "• ${it.label} — ${it.lastRssi} dBm (max ${it.maxRssi}) · ${((now - it.lastSeenMs) / 1000).coerceAtLeast(0)} s ago",
                    fontSize = 12.sp,
                )
            }
            Text("Vendor radio signatures only — not proof that police are present.", fontSize = 10.sp, color = ConsoleMuted)
        }
    }
}

private fun colorFor(c: LiveContact): Color = deviceCategoryColor(c.category, c.classification)

@Composable
private fun ContactRow(c: LiveContact, vm: RfWatchViewModel, selected: Boolean, onSelect: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) Color(0xFF102A2A) else ConsolePanel,
        ),
        border = BorderStroke(1.dp, if (selected) RadarCyan.copy(alpha = 0.75f) else ConsoleOutline),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.padding(end = 10.dp).size(10.dp).clip(CircleShape)
                    .background(colorFor(c)),
            )
            Column(Modifier.weight(1f)) {
                Text(c.label, fontSize = 14.sp)
                if (selected) Text("Selected · tap Locate to estimate", fontSize = 11.sp, color = RadarCyan)
                Text(
                    "${c.brand ?: "Unknown brand"} · ${c.category ?: c.band.name}",
                    fontSize = 11.sp, color = colorFor(c), maxLines = 1,
                )
                val est = c.estimate
                val detail = when {
                    est?.status == EstimateStatus.LOCATED ->
                        "${est.bearingFromUserDeg?.toInt() ?: "?"}° · ${est.distanceFromUserM?.toInt() ?: "?"} m · conf ${"%.2f".format(est.confidence)}"
                    est?.status == EstimateStatus.MOBILE -> "MOBILE · ${est.trend}"
                    est?.status == EstimateStatus.INSUFFICIENT -> "Need GPS-tagged readings while moving · ${c.lastRssi} dBm"
                    else -> "${c.classification} · ${c.lastRssi} dBm · ~${formatRssiDistance(rssiDistanceEstimateMeters(c.lastRssi))} m RSSI proxy"
                }
                Text(detail, fontSize = 12.sp, color = ConsoleMuted)
                if (est?.status == EstimateStatus.LOCATED && est.lat != null && est.lon != null) {
                    val uncertainty = est.ellipse?.semiMajorM?.let { " · ±${it.toInt()} m" } ?: ""
                    Text(
                        "Estimated device position ${String.format(Locale.US, "%.5f, %.5f", est.lat, est.lon)}$uncertainty",
                        fontSize = 11.sp, color = Color(0xFF9CA3AF),
                    )
                }
            }
            Button(onClick = { vm.estimate(c.id) {} }) { Text("Locate") }
        }
    }
}
