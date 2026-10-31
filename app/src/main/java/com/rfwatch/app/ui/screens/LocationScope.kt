package com.rfwatch.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.repo.LiveContact
import com.rfwatch.app.repo.LocateData
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.OkGreen
import com.rfwatch.app.ui.theme.RadarCyan
import com.rfwatch.core.df.CentroidEstimate
import com.rfwatch.core.df.EstimateStatus
import com.rfwatch.core.geo.LocalFrame
import com.rfwatch.core.geo.bearingDeg
import com.rfwatch.core.id.DeviceKind
import com.rfwatch.core.model.GpsFix
import kotlinx.coroutines.delay
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** Radar filter key: "ALL" or a [DeviceKind] name. */
internal const val RADAR_FILTER_ALL = "ALL"

internal fun LiveContact.matchesRadarFilter(filter: String) = filter == RADAR_FILTER_ALL || kind.name == filter

/** A device placed on the location plot, in metres east/north of the user. */
private data class Placed(val contact: LiveContact, val east: Double, val north: Double, val radiusM: Double, val driveBy: Boolean)

/**
 * Where each device most likely is: a drive-by LOCATED fix if one exists, otherwise an RSSI-weighted
 * centroid — but only when the sightings were spread out enough to say anything about direction.
 */
private fun placeContacts(contacts: List<LiveContact>, positions: Map<String, CentroidEstimate>, user: GpsFix?): Pair<List<Placed>, List<LiveContact>> {
    if (user == null) return emptyList<Placed>() to contacts
    val frame = LocalFrame(user.lat, user.lon)
    val placed = ArrayList<Placed>()
    val unplaced = ArrayList<LiveContact>()
    for (c in contacts) {
        val est = c.estimate
        val centroid = positions[c.id]
        when {
            est?.status == EstimateStatus.LOCATED && est.lat != null && est.lon != null -> {
                val e = frame.toEnu(est.lat!!, est.lon!!)
                placed += Placed(c, e.east, e.north, est.ellipse?.semiMajorM ?: 25.0, driveBy = true)
            }
            centroid != null && centroid.directional -> {
                val e = frame.toEnu(centroid.lat, centroid.lon)
                placed += Placed(c, e.east, e.north, centroid.radiusM, driveBy = false)
            }
            else -> unplaced += c
        }
    }
    return placed to unplaced
}

@Composable
internal fun RadarFilterChips(contacts: List<LiveContact>, filter: String, onFilter: (String) -> Unit) {
    val counts = contacts.groupingBy { it.kind }.eachCount()
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = filter == RADAR_FILTER_ALL, onClick = { onFilter(RADAR_FILTER_ALL) }, label = { Text("All ${contacts.size}") })
        val shown = DeviceKind.entries.filter { it == DeviceKind.LAW_ENFORCEMENT || (counts[it] ?: 0) > 0 || it.name == filter }
        shown.forEach { k ->
            FilterChip(selected = filter == k.name, onClick = { onFilter(k.name) }, label = { Text("${k.label} ${counts[k] ?: 0}") })
        }
    }
}

/**
 * North-up location plot centred on the user. The phone has no compass, so the plot cannot turn
 * with you — north is always up. Only devices with a real position estimate are drawn; the rest
 * are listed by signal-strength distance underneath (no invented angles).
 */
@Composable
internal fun LocationScopePanel(
    contacts: List<LiveContact>,
    positions: Map<String, CentroidEstimate>,
    user: GpsFix?,
    selectedId: String?,
    onSelect: (String) -> Unit,
) {
    val (placed, unplaced) = placeContacts(contacts, positions, user)
    val rangeM = niceRange(placed.maxOfOrNull { hypot(it.east, it.north) + it.radiusM * 0.5 } ?: 20.0)
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ConsolePanel),
        border = BorderStroke(1.dp, ConsoleOutline),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("LOCATION SCOPE · NORTH UP", fontSize = 10.sp, color = ConsoleMuted, letterSpacing = 1.sp)
                Text(if (user == null) "NO GPS FIX" else "${placed.size} located · ring ${(rangeM / 2).toInt()} m", fontSize = 10.sp, color = RadarCyan)
            }
            Canvas(
                Modifier.fillMaxWidth().height(260.dp).pointerInput(placed, rangeM) {
                    detectTapGestures { tap ->
                        val cx = size.width / 2f; val cy = size.height / 2f
                        val scale = min(cx, cy) * 0.92f / rangeM.toFloat()
                        placed.minByOrNull { p ->
                            hypot((cx + p.east.toFloat() * scale - tap.x).toDouble(), (cy - p.north.toFloat() * scale - tap.y).toDouble())
                        }?.let { p ->
                            val d = hypot((cx + p.east.toFloat() * scale - tap.x).toDouble(), (cy - p.north.toFloat() * scale - tap.y).toDouble())
                            if (d < 60) onSelect(p.contact.id)
                        }
                    }
                },
            ) { drawLocationScope(placed, rangeM, selectedId) }
            Text(
                "Dots are where each device most likely is, from where its signal was strongest as you moved " +
                    "(green ring = drive-by fit). Shaded circle = uncertainty. Walk around for better positions; " +
                    "tap a dot or row for its own chart.",
                fontSize = 10.sp, color = Color(0xFF78909A),
            )
            if (unplaced.isNotEmpty()) {
                Text("DISTANCE ONLY · NO DIRECTION YET (${unplaced.size})", fontSize = 10.sp, color = ConsoleMuted, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
                unplaced.sortedByDescending { it.lastRssi }.take(10).forEach { c ->
                    DistanceBar(c, selected = c.id == selectedId) { onSelect(c.id) }
                }
                if (unplaced.size > 10) Text("+${unplaced.size - 10} more below", fontSize = 10.sp, color = ConsoleMuted)
            }
        }
    }
}

@Composable
private fun DistanceBar(c: LiveContact, selected: Boolean, onClick: () -> Unit) {
    val meters = rssiDistanceEstimateMeters(c.lastRssi)
    val fraction = (1.0 - kotlin.math.log10(meters.coerceIn(0.5, 100.0) / 0.5) / kotlin.math.log10(200.0)).toFloat().coerceIn(0.05f, 1f)
    Row(
        Modifier.fillMaxWidth().pointerInput(c.id) { detectTapGestures { onClick() } },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(c.label.take(18), fontSize = 11.sp, color = if (selected) RadarCyan else Color.Unspecified, modifier = Modifier.weight(0.42f), maxLines = 1)
        Canvas(Modifier.weight(0.38f).height(8.dp)) {
            drawRect(Color(0xFF123240))
            drawRect(deviceCategoryColor(c.category, c.classification), size = size.copy(width = size.width * fraction))
        }
        Text("~${formatRssiDistance(meters)} m", fontSize = 11.sp, color = ConsoleMuted, modifier = Modifier.weight(0.2f))
    }
}

private fun DrawScope.drawLocationScope(placed: List<Placed>, rangeM: Double, selectedId: String?) {
    val cx = size.width / 2f; val cy = size.height / 2f
    val maxR = min(cx, cy) * 0.92f
    val scale = maxR / rangeM.toFloat()
    val grid = Color(0xFF1C4852)
    for (i in 1..2) drawCircle(grid, maxR * i / 2f, Offset(cx, cy), style = Stroke(1.3f))
    drawLine(grid, Offset(cx, cy - maxR), Offset(cx, cy + maxR), strokeWidth = 1f)
    drawLine(grid, Offset(cx - maxR, cy), Offset(cx + maxR, cy), strokeWidth = 1f)
    val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 24f; color = android.graphics.Color.argb(170, 145, 164, 176); textAlign = android.graphics.Paint.Align.CENTER }
    drawContext.canvas.nativeCanvas.drawText("N", cx, cy - maxR + 22f, paint)
    drawContext.canvas.nativeCanvas.drawText("${(rangeM / 2).toInt()} m", cx + maxR / 2f + 4f, cy - 6f, paint)
    drawContext.canvas.nativeCanvas.drawText("${rangeM.toInt()} m", cx + maxR - 30f, cy - 6f, paint)
    // You.
    drawCircle(RadarCyan, 7f, Offset(cx, cy))
    drawCircle(RadarCyan, 13f, Offset(cx, cy), style = Stroke(2f))
    placed.sortedBy { it.contact.id == selectedId }.forEach { p ->
        val px = cx + (p.east * scale).toFloat()
        val py = cy - (p.north * scale).toFloat()
        val color = deviceCategoryColor(p.contact.category, p.contact.classification)
        val selected = p.contact.id == selectedId
        drawCircle(color.copy(alpha = if (selected) 0.22f else 0.10f), (p.radiusM * scale).toFloat().coerceIn(6f, maxR), Offset(px, py))
        if (p.driveBy) drawCircle(OkGreen, 11f, Offset(px, py), style = Stroke(2.5f))
        if (selected) drawCircle(Color.White, 14f, Offset(px, py), style = Stroke(2.5f))
        drawCircle(color, if (selected) 8f else 6f, Offset(px, py))
    }
}

private fun niceRange(m: Double): Double {
    val steps = listOf(10.0, 20.0, 30.0, 50.0, 75.0, 100.0, 150.0, 200.0, 300.0, 500.0, 1000.0, 2000.0)
    return steps.firstOrNull { it >= m } ?: steps.last()
}

private fun compassWord(deg: Double): String =
    listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg % 360) + 360) % 360 / 45.0 + 0.5).toInt() % 8]

/**
 * One device on its own chart: every GPS-tagged hearing (colour/size = signal strength), your
 * current position, the RSSI-centroid estimate with its uncertainty circle, and a drive-by fit
 * when one exists. Refreshes every few seconds so you can walk toward the hot spot.
 */
@Composable
internal fun DeviceLocateChart(vm: RfWatchViewModel, contact: LiveContact, user: GpsFix?, onClose: () -> Unit) {
    var data by remember(contact.id) { mutableStateOf<LocateData?>(null) }
    LaunchedEffect(contact.id) {
        while (true) {
            data = vm.locateData(contact.id)
            delay(3_000)
        }
    }
    val d = data
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ConsolePanel),
        border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.6f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("LOCATE · ${contact.kind.label.uppercase()}", fontSize = 10.sp, color = RadarCyan, letterSpacing = 1.sp)
                    Text(contact.label, fontSize = 16.sp)
                    Text("${contact.brand ?: "Unknown brand"} · ${contact.band} · now ${contact.lastRssi} dBm", fontSize = 11.sp, color = ConsoleMuted)
                }
                TextButton(onClick = onClose) { Text("Close") }
            }
            val summary = when {
                d == null -> "Loading sightings…"
                d.sightings.isEmpty() -> "No GPS-tagged hearings yet — keep Location on while scanning."
                user != null && d.centroid != null && d.centroid.directional -> {
                    val frame = LocalFrame(user.lat, user.lon)
                    val e = frame.toEnu(d.centroid.lat, d.centroid.lon)
                    val dist = hypot(e.east, e.north)
                    "Most likely ~${dist.toInt()} m to the ${compassWord(bearingDeg(e.east, e.north))} of you (±${d.centroid.radiusM.toInt()} m), " +
                        "from ${d.centroid.samples} strong hearings over ${d.centroid.spreadM.toInt()} m of movement."
                }
                d.centroid != null -> "Heard only from about one spot (${d.centroid.spreadM.toInt()} m of movement) — direction unknown, " +
                    "roughly within ${d.centroid.radiusM.toInt()} m. Walk 10–20 m in a few directions; red dots mark where it is strongest."
                else -> "Only ${d.sightings.size} hearings so far — keep scanning and move around."
            }
            Text(summary, fontSize = 12.sp)
            Canvas(Modifier.fillMaxWidth().height(280.dp)) { if (d != null) drawLocateChart(d, user) }
            d?.sightings?.maxByOrNull { it.rssi }?.let { best ->
                Text(
                    "Strongest ${best.rssi} dBm at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(best.timestampMs))}" +
                        " · ${d.sightings.size} hearings · north up · blue = weak, red = strong",
                    fontSize = 10.sp, color = ConsoleMuted,
                )
            }
            if (d?.driveBy?.status == EstimateStatus.LOCATED) Text("Green ring = drive-by path-loss fit (±${d.driveBy.ellipse?.semiMajorM?.toInt() ?: "?"} m).", fontSize = 10.sp, color = OkGreen)
        }
    }
}

private fun DrawScope.drawLocateChart(d: LocateData, user: GpsFix?) {
    val center = d.centroid?.let { it.lat to it.lon } ?: user?.let { it.lat to it.lon } ?: d.sightings.firstOrNull()?.let { it.lat to it.lon } ?: return
    val frame = LocalFrame(center.first, center.second)
    val pts = d.sightings.map { s -> frame.toEnu(s.lat, s.lon) to s }
    val userE = user?.let { frame.toEnu(it.lat, it.lon) }
    val extent = max(
        10.0,
        (pts.map { max(kotlin.math.abs(it.first.east), kotlin.math.abs(it.first.north)) } +
            listOfNotNull(userE?.let { max(kotlin.math.abs(it.east), kotlin.math.abs(it.north)) }, d.centroid?.radiusM)).maxOrNull() ?: 10.0,
    ) * 1.15
    val cx = size.width / 2f; val cy = size.height / 2f
    val scale = min(cx, cy) / extent.toFloat()
    fun at(east: Double, north: Double) = Offset(cx + (east * scale).toFloat(), cy - (north * scale).toFloat())
    drawRect(Color(0xFF0A1820))
    val grid = Color(0xFF1C4852)
    drawLine(grid, Offset(cx, 0f), Offset(cx, size.height), strokeWidth = 1f)
    drawLine(grid, Offset(0f, cy), Offset(size.width, cy), strokeWidth = 1f)
    val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 22f; color = android.graphics.Color.argb(170, 145, 164, 176) }
    drawContext.canvas.nativeCanvas.drawText("N ↑   ±${extent.toInt()} m", 10f, 26f, paint)

    val minR = d.sightings.minOfOrNull { it.rssi } ?: -100
    val maxR = d.sightings.maxOfOrNull { it.rssi } ?: -40
    val span = (maxR - minR).coerceAtLeast(1)
    pts.sortedBy { it.second.rssi }.forEach { (e, s) ->
        val t = (s.rssi - minR).toFloat() / span
        val color = Color(red = 0.25f + 0.75f * t, green = 0.55f * (1 - t) + 0.2f, blue = 1f - 0.85f * t)
        drawCircle(color.copy(alpha = 0.85f), 4f + 6f * t, at(e.east, e.north))
    }
    d.centroid?.let { c ->
        val e = frame.toEnu(c.lat, c.lon)
        drawCircle(Color.White.copy(alpha = 0.10f), (c.radiusM * scale).toFloat(), at(e.east, e.north))
        drawCircle(Color.White.copy(alpha = 0.6f), (c.radiusM * scale).toFloat(), at(e.east, e.north), style = Stroke(1.5f))
        val p = at(e.east, e.north)
        drawLine(Color.White, Offset(p.x - 12f, p.y - 12f), Offset(p.x + 12f, p.y + 12f), strokeWidth = 4f)
        drawLine(Color.White, Offset(p.x - 12f, p.y + 12f), Offset(p.x + 12f, p.y - 12f), strokeWidth = 4f)
    }
    val drive = d.driveBy
    if (drive?.status == EstimateStatus.LOCATED && drive.lat != null && drive.lon != null) {
        val e = frame.toEnu(drive.lat!!, drive.lon!!)
        drawCircle(OkGreen, 12f, at(e.east, e.north), style = Stroke(3f))
    }
    userE?.let {
        drawCircle(RadarCyan, 8f, at(it.east, it.north))
        drawCircle(RadarCyan, 15f, at(it.east, it.north), style = Stroke(2.5f))
        drawContext.canvas.nativeCanvas.drawText("YOU", at(it.east, it.north).x + 18f, at(it.east, it.north).y + 8f, paint)
    }
}
