package com.rfwatch.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    val facing = heading.deg ?: 0.0
    val priorityCount = list.count { it.classification == "PRIORITY" || it.classification == "TRACKER" }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(key = "radar-header") {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LIVE SPECTRUM", fontSize = 10.sp, color = RadarCyan, letterSpacing = 1.5.sp)
                    Text("${list.size} signals in view", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                }
                androidx.compose.material3.Surface(
                    color = if (priorityCount > 0) DangerRed.copy(alpha = 0.12f) else ConsolePanelRaised,
                    shape = CircleShape,
                    border = BorderStroke(1.dp, if (priorityCount > 0) DangerRed.copy(alpha = 0.45f) else ConsoleOutline),
                ) {
                    Text("$priorityCount WATCH", Modifier.padding(horizontal = 12.dp, vertical = 7.dp), fontSize = 10.sp, color = if (priorityCount > 0) DangerRed else ConsoleMuted)
                }
            }
        }
        law?.let { encounter ->
            if (encounter.active || System.currentTimeMillis() - encounter.lastSeenMs < LAW_BANNER_KEEP_MS) {
                item(key = "law-banner") { LawBanner(encounter) }
            }
        }
        item(key = "radar-scope") {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = ConsolePanel),
                border = BorderStroke(1.dp, ConsoleOutline),
            ) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                        Text("PROXIMITY SCOPE", fontSize = 10.sp, color = ConsoleMuted, letterSpacing = 1.sp)
                        Text(
                            heading.deg?.let { "HEADING ${it.toInt()}°" } ?: "HEADING UNAVAILABLE",
                            fontSize = 11.sp, color = if (heading.deg == null) WarnAmber else RadarCyan,
                        )
                    }
                    Canvas(Modifier.fillMaxWidth().height(205.dp).padding(vertical = 6.dp)) {
                        drawRadar(list, facing, selectedContactId)
                    }
                    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            LegendDot(RadarCyan, "You / BLE")
                            LegendDot(SignalBlue, "Wi-Fi")
                            LegendDot(RadarPink, "Tracker")
                        }
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                            LegendDot(DangerRed, "Axon / law")
                            LegendDot(WarnAmber, "Camera / IoT")
                            LegendDot(Color(0xFFB79CFF), "Fleet")
                        }
                    }
                    Text(
                        "Without a measured bearing, dots sit in their category's sector (stable order) and closer to the centre means a stronger signal — " +
                            "a rough RSSI proxy, not a distance. Only drive-by fixes are plotted at a real direction.",
                        fontSize = 10.sp, color = Color(0xFF78909A),
                    )
                }
            }
        }
        item(key = "live-devices-heading") {
            Row(Modifier.fillMaxWidth().padding(top = 4.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("LIVE DETECTIONS", fontSize = 10.sp, color = ConsoleMuted, letterSpacing = 1.sp)
                Text("${list.size} now · tap to select", fontSize = 10.sp, color = ConsoleMuted)
            }
        }
        if (list.isEmpty()) {
            item(key = "no-live-devices") { EmptyConsoleNotice("No live signals yet", "BLE and Wi-Fi detections appear here as the monitors receive them.") }
        }
        items(list, key = { "live-${it.id}" }) { c ->
            ContactRow(c, vm, selected = selectedContactId == c.id) { selectedContactId = c.id }
        }
        item(key = "inventory-heading") {
            Column(Modifier.padding(top = 10.dp)) {
                Text("DEVICE INVENTORY", fontSize = 10.sp, color = RadarCyan, letterSpacing = 1.3.sp)
                Text("${devices.size} saved records", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
                Text("Previously heard devices and their latest signal details", fontSize = 11.sp, color = ConsoleMuted)
            }
        }
        if (devices.isEmpty()) {
            item(key = "empty-inventory") { EmptyConsoleNotice("Inventory is empty", "Records will collect as BLE and Wi-Fi monitoring runs.") }
        }
        items(devices, key = { "saved-${it.id}" }) { device ->
            InventoryRow(device)
        }
    }
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

private fun DrawScope.drawRadar(contacts: List<LiveContact>, facingDeg: Double, selectedId: String?) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = min(cx, cy) * 0.92f
    val grid = Color(0xFF1C4852)

    // Range rings.
    for (i in 1..3) {
        drawCircle(grid.copy(alpha = if (i == 3) 0.9f else 0.55f), radius = maxR * i / 3f, center = Offset(cx, cy), style = Stroke(if (i == 3) 2f else 1.2f))
    }
    // Light compass spokes and perimeter ticks keep the scope legible without implying a distance scale.
    for (i in 0 until 8) {
        val a = Math.toRadians(i * 45.0)
        drawLine(grid.copy(alpha = 0.55f), Offset(cx, cy), Offset(cx + (maxR * cos(a)).toFloat(), cy + (maxR * sin(a)).toFloat()), strokeWidth = 1f)
    }
    for (i in 0 until 36) {
        val a = Math.toRadians(i * 10.0)
        val inner = maxR * if (i % 3 == 0) 0.92f else 0.96f
        drawLine(
            grid.copy(alpha = 0.8f),
            Offset(cx + (inner * cos(a)).toFloat(), cy + (inner * sin(a)).toFloat()),
            Offset(cx + (maxR * cos(a)).toFloat(), cy + (maxR * sin(a)).toFloat()),
            strokeWidth = if (i % 3 == 0) 1.5f else 1f,
        )
    }
    // Own-position marker.
    drawCircle(RadarCyan, radius = 5f, center = Offset(cx, cy))

    val maxDist = (contacts.mapNotNull { it.estimate?.distanceFromUserM }.maxOrNull() ?: 100.0).coerceAtLeast(50.0)

    // Category lanes for contacts without a real bearing: fixed sectors, stable order by id.
    val lanes = contacts.filterNot { it.hasRealBearing() }.groupBy { laneOf(it) }
        .mapValues { (_, list) -> list.sortedBy { it.id } }
    val labelPaint = android.graphics.Paint().apply {
        isAntiAlias = true; textSize = 22f; color = android.graphics.Color.argb(150, 145, 164, 176)
        textAlign = android.graphics.Paint.Align.CENTER
    }
    RADAR_LANES.forEachIndexed { i, name ->
        val center = Math.toRadians(i * LANE_DEG)
        drawLine(grid.copy(alpha = 0.9f), Offset(cx, cy),
            Offset(cx + (maxR * sin(center - Math.toRadians(LANE_DEG / 2))).toFloat(), cy - (maxR * cos(center - Math.toRadians(LANE_DEG / 2))).toFloat()),
            strokeWidth = 1.4f)
        drawContext.canvas.nativeCanvas.drawText(
            name, cx + (maxR * 0.78f * sin(center)).toFloat(), cy - (maxR * 0.78f * cos(center)).toFloat() + 8f, labelPaint,
        )
    }

    contacts.forEach { c ->
        val est = c.estimate
        val color = colorFor(c)
        if (est != null && est.status == EstimateStatus.LOCATED && est.bearingFromUserDeg != null) {
            val rel = Math.toRadians(est.bearingFromUserDeg!! - facingDeg)
            val rNorm = ((est.distanceFromUserM ?: maxDist) / maxDist).coerceIn(0.1, 1.0)
            val r = maxR * rNorm.toFloat()
            val px = cx + (r * sin(rel)).toFloat()
            val py = cy - (r * cos(rel)).toFloat()
            // Uncertainty wedge: angular half-width from the ellipse vs distance.
            val halfWedge = est.ellipse?.let {
                Math.toDegrees(Math.atan2(it.semiMajorM, (est.distanceFromUserM ?: maxDist).coerceAtLeast(1.0)))
            } ?: 15.0
            drawWedge(cx, cy, r, est.bearingFromUserDeg!! - facingDeg, halfWedge, color.copy(alpha = 0.18f))
            if (c.id == selectedId) drawCircle(RadarCyan, radius = 15f, center = Offset(px, py), style = Stroke(2f))
            drawCircle(color, radius = 7f, center = Offset(px, py))
        } else {
            // Bearing unknown → the contact's category lane; radius from signal strength.
            val lane = laneOf(c)
            val members = lanes[lane].orEmpty()
            val index = members.indexOfFirst { it.id == c.id }.coerceAtLeast(0)
            val spread = LANE_DEG * 0.8
            val angleDeg = lane * LANE_DEG - spread / 2 + spread * (index + 0.5) / members.size.coerceAtLeast(1)
            val strength = ((c.lastRssi + 100.0) / 70.0).coerceIn(0.0, 1.0)
            val r = maxR * (0.95 - 0.8 * strength).toFloat()
            val a = Math.toRadians(angleDeg)
            val px = cx + (r * sin(a)).toFloat()
            val py = cy - (r * cos(a)).toFloat()
            if (c.id == selectedId) drawCircle(RadarCyan, radius = 11f, center = Offset(px, py), style = Stroke(2f))
            drawCircle(colorFor(c).copy(alpha = 0.85f), radius = if (c.id == selectedId) 6f else 4.5f, center = Offset(px, py))
        }
    }
}

private val RADAR_LANES = listOf("TRACKER", "LAW", "CAMERA", "VEHICLE", "WI-FI", "BLE")
private const val LANE_DEG = 60.0
private const val LAW_BANNER_KEEP_MS = 10 * 60_000L

private fun LiveContact.hasRealBearing() =
    estimate?.status == EstimateStatus.LOCATED && estimate.bearingFromUserDeg != null

private fun laneOf(c: LiveContact): Int {
    val label = c.category.orEmpty().lowercase()
    return when {
        c.trackerType != null || "tracker" in label -> 0
        "axon" in label || "law" in label || "police" in label -> 1
        "camera" in label || "iot" in label -> 2
        "fleet" in label || "vehicle" in label -> 3
        c.band == com.rfwatch.core.model.Band.WIFI -> 4
        else -> 5
    }
}

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

private fun DrawScope.drawWedge(cx: Float, cy: Float, r: Float, centerDeg: Double, halfDeg: Double, color: Color) {
    val steps = 10
    val start = centerDeg - halfDeg
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(cx, cy)
        for (i in 0..steps) {
            val a = Math.toRadians(start + (2 * halfDeg) * i / steps)
            lineTo(cx + (r * sin(a)).toFloat(), cy - (r * cos(a)).toFloat())
        }
        close()
    }
    drawPath(path, color)
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
