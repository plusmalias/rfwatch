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
    drones: Collection<com.rfwatch.app.repo.DroneTrack> = emptyList(),
    onSelect: (String) -> Unit,
) {
    val (placed, unplaced) = placeContacts(contacts, positions, user)
    val droneMarks = if (user == null) emptyList() else drones.mapNotNull { d ->
        val frame = LocalFrame(user.lat, user.lon)
        val lat = d.info.lat; val lon = d.info.lon
        if (lat == null || lon == null) null else {
            val e = frame.toEnu(lat, lon)
            val opLat = d.info.operatorLat; val opLon = d.info.operatorLon
            val op = if (opLat != null && opLon != null) frame.toEnu(opLat, opLon) else null
            DroneMark(e.east, e.north, op?.east, op?.north)
        }
    }
    val rangeM = if (placed.isEmpty() && droneMarks.isEmpty() && unplaced.isNotEmpty()) {
        // Range rings only: zoom to fit them so close signals are distinguishable (no positions are claimed).
        val far = unplaced.sortedByDescending { it.lastRssi }.take(MAX_RANGE_RINGS).maxOf { rssiDistanceEstimateMeters(it.lastRssi) }
        listOf(3.0, 5.0, 10.0, 20.0, 30.0, 50.0, 100.0).firstOrNull { it >= far * 1.15 } ?: 100.0
    } else niceRange(
        // Fit the dots themselves (not their uncertainty circles), ignoring the farthest 20 %:
        // outliers are pinned to the rim instead of shrinking the cluster near you to a blob.
        maxOf(
            placed.map { hypot(it.east, it.north) }.sorted().let { d -> if (d.isEmpty()) 0.0 else d[((d.size - 1) * 0.8).toInt()] * 1.15 },
            droneMarks.flatMap { listOfNotNull(hypot(it.east, it.north), it.opEast?.let { e -> hypot(e, it.opNorth ?: 0.0) }) }.maxOrNull() ?: 0.0,
        ).takeIf { it > 0 } ?: 20.0,
    )
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ConsolePanel),
        border = BorderStroke(1.dp, ConsoleOutline),
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("LOCATION SCOPE · NORTH UP", fontSize = 10.sp, color = ConsoleMuted, letterSpacing = 1.sp)
                Text(if (user == null) "NO GPS FIX" else "${placed.size} located · ${unplaced.size} by distance", fontSize = 10.sp, color = RadarCyan)
            }
            Canvas(
                Modifier.fillMaxWidth().height(260.dp).pointerInput(placed, rangeM) {
                    detectTapGestures { tap ->
                        val cx = size.width / 2f; val cy = size.height / 2f
                        val scale = min(cx, cy) * 0.92f / rangeM.toFloat()
                        val maxR = min(cx, cy) * 0.92f
                        placed.minByOrNull { p ->
                            val o = plotOffset(p, cx, cy, scale, maxR)
                            hypot((o.x - tap.x).toDouble(), (o.y - tap.y).toDouble())
                        }?.let { p ->
                            val o = plotOffset(p, cx, cy, scale, maxR)
                            if (hypot((o.x - tap.x).toDouble(), (o.y - tap.y).toDouble()) < 60) onSelect(p.contact.id)
                        }
                    }
                },
            ) {
                // Devices without a direction are drawn as dashed range rings: "about this far, direction unknown".
                val rings = unplaced.sortedByDescending { it.lastRssi }.take(MAX_RANGE_RINGS).map {
                    rssiDistanceEstimateMeters(it.lastRssi) to deviceCategoryColor(it.category, it.classification)
                }
                drawLocationScope(placed, rangeM, selectedId, droneMarks, rings)
            }
            if (placed.isEmpty() && unplaced.isNotEmpty()) {
                Text(
                    "No directions yet — you haven't moved enough. Dashed rings show how far the ${minOf(unplaced.size, MAX_RANGE_RINGS)} strongest " +
                        "signals are (direction unknown). Walk 25 m or more — a lap of the house, yard or street — and dots appear where each signal was strongest.",
                    fontSize = 11.sp, color = com.rfwatch.app.ui.theme.WarnAmber,
                )
            }
            if (drones.isNotEmpty()) {
                Text(
                    "DRONES: " + drones.joinToString { (it.info.serial ?: "unidentified") + " (${it.source})" } +
                        " — ◆ drone position, P pilot position (as broadcast by the drone).",
                    fontSize = 11.sp, color = com.rfwatch.app.ui.theme.DangerRed,
                )
            }
            Text(
                "Dots are where each device most likely is, from where its signal was strongest as you moved " +
                    "(needs 25 m+ of walking/driving; green ring = drive-by fit). Circle = uncertainty. " +
                    "Tap a dot or row for its own chart.",
                fontSize = 10.sp, color = Color(0xFF78909A),
            )
            if (unplaced.isNotEmpty()) {
                Text("DISTANCE ONLY · NO DIRECTION YET (${unplaced.size})", fontSize = 10.sp, color = ConsoleMuted, letterSpacing = 1.sp, modifier = Modifier.padding(top = 4.dp))
                // Every device on one chart, then the individual rows (strongest first).
                DistanceSwarm(unplaced, selectedId, onSelect)
                var showAll by remember { mutableStateOf(false) }
                val sorted = unplaced.sortedByDescending { it.lastRssi }
                (if (showAll) sorted else sorted.take(DISTANCE_ROWS)).forEach { c ->
                    DistanceBar(c, selected = c.id == selectedId) { onSelect(c.id) }
                }
                if (unplaced.size > DISTANCE_ROWS) {
                    TextButton(onClick = { showAll = !showAll }) {
                        Text(if (showAll) "Show strongest $DISTANCE_ROWS" else "Show all ${unplaced.size}", fontSize = 11.sp)
                    }
                }
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

private class SwarmLayout { var value: List<Pair<LiveContact, Offset>> = emptyList() }

private const val DISTANCE_ROWS = 10
private const val SWARM_MIN_M = 0.5
private const val SWARM_MAX_M = 100.0

/** 0..1 position on a log distance axis from [SWARM_MIN_M] to [SWARM_MAX_M]. */
private fun swarmX(meters: Double): Float =
    (kotlin.math.log10(meters.coerceIn(SWARM_MIN_M, SWARM_MAX_M) / SWARM_MIN_M) / kotlin.math.log10(SWARM_MAX_M / SWARM_MIN_M)).toFloat()

/**
 * One chart for every distance-only device: a log-scale strip (0.5–100 m, you at the left) with a
 * dot per device, stacked where they overlap. Distance is an RSSI guess; direction is unknown.
 */
@Composable
private fun DistanceSwarm(contacts: List<LiveContact>, selectedId: String?, onSelect: (String) -> Unit) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val dotPx = with(density) { 9.dp.toPx() }
    val heightDp = 96.dp
    // Layout is computed in pixel space inside the canvas; keep the last layout for hit-testing.
    val layout = remember { SwarmLayout() }
    Canvas(
        Modifier.fillMaxWidth().height(heightDp).pointerInput(contacts) {
            detectTapGestures { tap ->
                layout.value.minByOrNull { (_, o) -> hypot((o.x - tap.x).toDouble(), (o.y - tap.y).toDouble()) }
                    ?.takeIf { (_, o) -> hypot((o.x - tap.x).toDouble(), (o.y - tap.y).toDouble()) < dotPx * 2.5 }
                    ?.let { (c, _) -> onSelect(c.id) }
            }
        },
    ) {
        val axisY = size.height - 18f
        val left = 10f; val right = size.width - 10f
        val usable = right - left
        val grid = Color(0xFF1C4852)
        drawLine(grid, Offset(left, axisY), Offset(right, axisY), strokeWidth = 1.5f)
        val paint = android.graphics.Paint().apply {
            isAntiAlias = true; textSize = 22f; color = android.graphics.Color.argb(170, 145, 164, 176)
            textAlign = android.graphics.Paint.Align.CENTER
        }
        listOf(1.0, 3.0, 10.0, 30.0, 100.0).forEach { m ->
            val x = left + swarmX(m) * usable
            drawLine(grid, Offset(x, axisY - 4f), Offset(x, axisY + 4f), strokeWidth = 1.5f)
            drawContext.canvas.nativeCanvas.drawText("${m.toInt()} m", x, size.height - 1f, paint)
        }
        // Stack dots in columns one dot wide; shrink dots if a column would overflow the chart.
        val columns = HashMap<Int, Int>()
        val raw = contacts.sortedByDescending { it.lastRssi }.map { c ->
            val x = left + swarmX(rssiDistanceEstimateMeters(c.lastRssi)) * usable
            val col = (x / dotPx).toInt()
            val stack = columns.merge(col, 1, Int::plus)!! - 1
            Triple(c, col, stack)
        }
        val maxStack = (columns.values.maxOrNull() ?: 1).coerceAtLeast(1)
        val step = minOf(dotPx, (axisY - 6f) / maxStack)
        val placedDots = raw.map { (c, col, stack) ->
            c to Offset(col * dotPx + dotPx / 2f, axisY - 6f - step / 2f - stack * step)
        }
        layout.value = placedDots
        placedDots.forEach { (c, o) ->
            val color = deviceCategoryColor(c.category, c.classification)
            drawCircle(color, step * 0.45f, o)
            if (c.id == selectedId) drawCircle(RadarCyan, step * 0.45f + 4f, o, style = Stroke(2.5f))
        }
    }
}

private data class DroneMark(val east: Double, val north: Double, val opEast: Double?, val opNorth: Double?)

private const val MAX_RANGE_RINGS = 8

private fun DrawScope.drawLocationScope(
    placed: List<Placed>, rangeM: Double, selectedId: String?, drones: List<DroneMark> = emptyList(),
    rangeRings: List<Pair<Double, Color>> = emptyList(),
) {
    val cx = size.width / 2f; val cy = size.height / 2f
    val maxR = min(cx, cy) * 0.92f
    val scale = maxR / rangeM.toFloat()
    val grid = Color(0xFF1C4852)
    for (i in 1..2) drawCircle(grid, maxR * i / 2f, Offset(cx, cy), style = Stroke(1.3f))
    drawLine(grid, Offset(cx, cy - maxR), Offset(cx, cy + maxR), strokeWidth = 1f)
    drawLine(grid, Offset(cx - maxR, cy), Offset(cx + maxR, cy), strokeWidth = 1f)
    val paint = android.graphics.Paint().apply { isAntiAlias = true; textSize = 24f; color = android.graphics.Color.argb(170, 145, 164, 176); textAlign = android.graphics.Paint.Align.CENTER }
    drawContext.canvas.nativeCanvas.drawText("N", cx, cy - maxR + 22f, paint)
    fun metres(v: Double) = if (v < 10 && v % 1.0 != 0.0) "%.1f m".format(v) else "${v.toInt()} m"
    drawContext.canvas.nativeCanvas.drawText(metres(rangeM / 2), cx + maxR / 2f + 4f, cy - 6f, paint)
    drawContext.canvas.nativeCanvas.drawText(metres(rangeM), cx + maxR - 30f, cy - 6f, paint)
    val dash = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(12f, 10f))
    rangeRings.forEach { (meters, color) ->
        drawCircle(color.copy(alpha = 0.55f), (meters * scale).toFloat().coerceIn(10f, maxR), Offset(cx, cy), style = Stroke(2f, pathEffect = dash))
    }
    // You.
    drawCircle(RadarCyan, 7f, Offset(cx, cy))
    drawCircle(RadarCyan, 13f, Offset(cx, cy), style = Stroke(2f))
    val red = com.rfwatch.app.ui.theme.DangerRed
    drones.forEach { d ->
        val px = cx + (d.east * scale).toFloat(); val py = cy - (d.north * scale).toFloat()
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(px, py - 12f); lineTo(px + 12f, py); lineTo(px, py + 12f); lineTo(px - 12f, py); close()
        }
        drawPath(path, red)
        if (d.opEast != null && d.opNorth != null) {
            val ox = cx + (d.opEast * scale).toFloat(); val oy = cy - (d.opNorth * scale).toFloat()
            drawLine(red.copy(alpha = 0.5f), Offset(px, py), Offset(ox, oy), strokeWidth = 2f,
                pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(10f, 8f)))
            drawCircle(com.rfwatch.app.ui.theme.WarnAmber, 9f, Offset(ox, oy))
            drawContext.canvas.nativeCanvas.drawText("P", ox, oy + 8f, paint)
        }
    }
    placed.sortedBy { it.contact.id == selectedId }.forEach { p ->
        val o = plotOffset(p, cx, cy, scale, maxR)
        val pinned = hypot(p.east, p.north) * scale > maxR - 8f
        val color = deviceCategoryColor(p.contact.category, p.contact.classification)
        val selected = p.contact.id == selectedId
        // Uncertainty only for the selected device — twenty overlapping circles hide every dot.
        if (selected && !pinned) {
            val r = (p.radiusM * scale).toFloat().coerceIn(6f, maxR * 0.6f)
            drawCircle(color.copy(alpha = 0.20f), r, o)
            drawCircle(color.copy(alpha = 0.8f), r, o, style = Stroke(2f))
        }
        if (p.driveBy) drawCircle(OkGreen, 11f, o, style = Stroke(2.5f))
        if (selected) drawCircle(Color.White, 14f, o, style = Stroke(2.5f))
        // Off-scale devices sit on the rim in their true direction, drawn hollow.
        if (pinned) drawCircle(color, if (selected) 8f else 6f, o, style = Stroke(3f))
        else drawCircle(color, if (selected) 8f else 6f, o)
    }
}

/** Screen position of a placed device; anything beyond the range is pinned to the rim in its direction. */
private fun plotOffset(p: Placed, cx: Float, cy: Float, scale: Float, maxR: Float): Offset {
    val dist = hypot(p.east, p.north)
    val px = (dist * scale).toFloat()
    val k = if (px > maxR - 8f && dist > 0) (maxR - 8f) / px else 1f
    return Offset(cx + (p.east * scale).toFloat() * k, cy - (p.north * scale).toFloat() * k)
}

private fun niceRange(m: Double): Double {
    // Never zoom tighter than 20 m: phone GPS and RSSI cannot support finer placement.
    val steps = listOf(20.0, 30.0, 50.0, 75.0, 100.0, 150.0, 200.0, 300.0, 500.0, 1000.0, 2000.0)
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
            com.rfwatch.app.ui.DeviceLinks(vm, contact.id, here = com.rfwatch.app.ui.DeviceDest.LOCATE)
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
                else -> "Only ${d.hearings} hearings from ${d.sightings.size} spots so far — keep scanning and move around."
            }
            Text(summary, fontSize = 12.sp)
            Canvas(Modifier.fillMaxWidth().height(280.dp)) { if (d != null) drawLocateChart(d, user) }
            d?.sightings?.maxByOrNull { it.rssi }?.let { best ->
                Text(
                    "Strongest ${best.rssi} dBm at ${java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(best.timestampMs))}" +
                        " · ${d.hearings} hearings at ${d.sightings.size} spots · bigger dot = heard more there · blue = weak, red = strong",
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
        // One dot per spot: colour = strongest signal there, size grows with hearings at that spot.
        val r = 4f + 6f * t + (kotlin.math.ln(s.count.toFloat()) * 2.2f).coerceAtMost(8f)
        drawCircle(color.copy(alpha = 0.85f), r, at(e.east, e.north))
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
