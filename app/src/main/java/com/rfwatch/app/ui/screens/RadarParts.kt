package com.rfwatch.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.Flight
import androidx.compose.material.icons.filled.Headphones
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.LocalHospital
import androidx.compose.material.icons.filled.LocalPolice
import androidx.compose.material.icons.filled.LocalShipping
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Sell
import androidx.compose.material.icons.filled.Tv
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Watch
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.data.DeviceEntity
import com.rfwatch.app.repo.LiveContact
import com.rfwatch.app.repo.ScanSessionStatus
import com.rfwatch.app.ui.toRowInfo
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.app.ui.theme.ConsolePanelRaised
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.OkGreen
import com.rfwatch.app.ui.theme.RadarCyan
import com.rfwatch.app.ui.theme.RadarPink
import com.rfwatch.core.df.Mobility
import com.rfwatch.core.df.RssiTrend
import com.rfwatch.core.id.DeviceKind
import com.rfwatch.core.model.Band
import com.rfwatch.core.model.RadioObservation

/** BLE contacts count as "nearby now" for this long after the last advert; Wi-Fi scans are rate-limited, so longer. */
internal const val LIVE_BLE_MS = 60_000L
internal const val LIVE_WIFI_MS = 180_000L

/** Heard recently enough to be around right now (contacts are never pruned in the repository). */
internal fun LiveContact.isLiveNow(nowMs: Long): Boolean =
    nowMs - lastSeenMs <= if (band == Band.WIFI) LIVE_WIFI_MS else LIVE_BLE_MS

/** Same rule as the Devices "Threats" chip when the stored row is known, else the live classification. */
private val MAC_LIKE = Regex("^[0-9A-Fa-f]{2}(:[0-9A-Fa-f]{2}){5}$")

/** A readable name: the advertised name, or "Brand kind ·tail" instead of a raw address. */
internal fun LiveContact.shortName(): String = when {
    !MAC_LIKE.matches(label) -> label
    model != null -> (if (brand == null || model.contains(brand, ignoreCase = true)) model else "$brand $model") + " ·${id.takeLast(5)}"
    else -> "${brand ?: kind.label} ·${id.takeLast(5)}"
}

internal fun LiveContact.isThreatNow(stored: DeviceEntity?): Boolean =
    stored?.let { com.rfwatch.app.ui.isThreat(it, it.effectiveKind()) }
        ?: (classification == "PRIORITY" || classification == "TRACKER")

internal fun kindIcon(kind: DeviceKind): ImageVector = when (kind) {
    DeviceKind.TRACKER -> Icons.Filled.Sell
    DeviceKind.DRONE -> Icons.Filled.Flight
    DeviceKind.LAW_ENFORCEMENT -> Icons.Filled.LocalPolice
    DeviceKind.EMERGENCY -> Icons.Filled.LocalHospital
    DeviceKind.ATTACK_TOOL -> Icons.Filled.Warning
    DeviceKind.CAMERA_IOT -> Icons.Filled.Videocam
    DeviceKind.FLEET -> Icons.Filled.LocalShipping
    DeviceKind.VEHICLE -> Icons.Filled.DirectionsCar
    DeviceKind.PHONE -> Icons.Filled.PhoneAndroid
    DeviceKind.COMPUTER -> Icons.Filled.Computer
    DeviceKind.AUDIO -> Icons.Filled.Headphones
    DeviceKind.WEARABLE -> Icons.Filled.Watch
    DeviceKind.TV_MEDIA -> Icons.Filled.Tv
    DeviceKind.HEALTH -> Icons.Filled.Favorite
    DeviceKind.INPUT -> Icons.Filled.Keyboard
    DeviceKind.SMART_HOME -> Icons.Filled.Home
    DeviceKind.BEACON -> Icons.Filled.Campaign
    DeviceKind.HOTSPOT -> Icons.Filled.WifiTethering
    DeviceKind.NETWORK -> Icons.Filled.Router
    DeviceKind.UNKNOWN -> Icons.Filled.Bluetooth
}

internal fun LiveContact.icon(): ImageVector =
    if (kind == DeviceKind.UNKNOWN && band == Band.WIFI) Icons.Filled.Wifi else kindIcon(kind)

/**
 * Approaching / receding per contact from the readings this screen has seen (contacts publish
 * ≤ 1/s), using the app's [Mobility.trend] (60 s EWMA slope, 3 dB deadband).
 */
internal class TrendTracker {
    private val history = HashMap<String, ArrayDeque<RadioObservation>>()
    private val lastTs = HashMap<String, Long>()

    /** Recent RSSI readings (oldest first) for a sparkline. */
    fun history(id: String): List<Int> = history[id]?.map { it.rssi }.orEmpty()

    fun update(contacts: Collection<LiveContact>, nowMs: Long): Map<String, RssiTrend> {
        val out = HashMap<String, RssiTrend>(contacts.size)
        for (c in contacts) {
            val h = history.getOrPut(c.id) { ArrayDeque() }
            if (lastTs[c.id] != c.lastSeenMs) {
                lastTs[c.id] = c.lastSeenMs
                h.addLast(RadioObservation(c.id, c.band, c.lastRssi, c.lastSeenMs))
            }
            while (h.isNotEmpty() && nowMs - h.first().timestampMs > Mobility.TREND_WINDOW_MS) h.removeFirst()
            out[c.id] = Mobility.trend(h, nowMs)
        }
        history.keys.retainAll(contacts.map { it.id }.toSet())
        lastTs.keys.retainAll(history.keys)
        return out
    }
}

internal fun RssiTrend.arrow(): String? = when (this) {
    RssiTrend.APPROACHING -> "↑"
    RssiTrend.RECEDING -> "↓"
    else -> null
}

internal fun RssiTrend.words(): String = when (this) {
    RssiTrend.APPROACHING -> "getting closer"
    RssiTrend.RECEDING -> "moving away"
    RssiTrend.STEADY -> "steady"
    RssiTrend.UNKNOWN -> "trend not known yet"
}

/** Compact session indicator for the Radar header; tap opens [SessionDialog]. */
@Composable
internal fun RecPill(status: ScanSessionStatus, onClick: () -> Unit) {
    val active = status.isActive
    val color = if (active) RadarCyan else ConsoleMuted
    Surface(
        modifier = Modifier.clip(CircleShape).clickable(onClick = onClick),
        color = color.copy(alpha = 0.12f), shape = CircleShape, border = BorderStroke(1.dp, color.copy(alpha = 0.5f)),
    ) {
        Text(
            if (active) "● REC ${status.observationCount}" else "○ REC",
            Modifier.padding(horizontal = 10.dp, vertical = 5.dp), fontSize = 11.sp, color = color, letterSpacing = 0.6.sp,
        )
    }
}

@Composable
internal fun SessionDialog(status: ScanSessionStatus, onStart: () -> Unit, onSave: () -> Unit, onClear: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (status.isActive) "Recording session" else "Scan session") },
        text = {
            Text(
                if (status.isActive) "${status.observationCount} observations since " +
                    (status.startedAtMs?.let { java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it)) } ?: "now") +
                    ".\n\nSave ends capture and keeps its observations. Clear deletes only this session's observations; your device inventory stays."
                else "Monitoring runs all the time. A session groups detections (e.g. one walk or one room check) so you can save or discard them together.",
                fontSize = 13.sp,
            )
        },
        confirmButton = {
            if (status.isActive) TextButton(onClick = { onSave(); onDismiss() }) { Text("Save") }
            else TextButton(onClick = { onStart(); onDismiss() }) { Text("Start") }
        },
        dismissButton = {
            Row {
                if (status.isActive) TextButton(onClick = { onDismiss(); onClear() }) { Text("Clear…", color = DangerRed) }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

/** Pop-up card for the device tapped on the plot or in the list. */
@Composable
internal fun SelectedDeviceCard(
    contact: LiveContact,
    stored: DeviceEntity?,
    trend: RssiTrend,
    recent: List<Int> = emptyList(),
    positioned: Boolean,
    chartOpen: Boolean,
    onHunt: () -> Unit,
    onChart: () -> Unit,
    onDetails: () -> Unit,
    onClose: () -> Unit,
) {
    val info = contact.toRowInfoSafe(stored)
    val threat = contact.isThreatNow(stored)
    val accent = if (threat) DangerRed else deviceCategoryColor(contact.category, contact.classification)
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ConsolePanelRaised),
        border = BorderStroke(1.dp, accent.copy(alpha = 0.7f)),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
    ) {
        Column(Modifier.padding(start = 14.dp, end = 6.dp, top = 8.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(contact.icon(), null, tint = accent, modifier = Modifier.size(22.dp))
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(info.first, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
                    Text(info.second, fontSize = 11.sp, color = accent, maxLines = 1)
                }
                SignalBars(contact.lastRssi, accent, Modifier.padding(start = 6.dp))
                IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close", tint = ConsoleMuted) }
            }
            val ago = ((System.currentTimeMillis() - contact.lastSeenMs) / 1000).coerceAtLeast(0)
            Text(
                "${contact.lastRssi} dBm · ~${formatRssiDistance(rssiDistanceEstimateMeters(contact.lastRssi))} m · ${trend.words()}" +
                    (if (ago > 5) " · heard ${ago}s ago" else "") + if (positioned) " · on the plot" else " · direction unknown",
                fontSize = 12.sp, color = when (trend) { RssiTrend.APPROACHING -> RadarPink; else -> Color(0xFFB7C6CD) },
            )
            if (recent.size >= 2) Row(verticalAlignment = Alignment.CenterVertically) {
                Text("LAST MIN ", fontSize = 9.sp, color = ConsoleMuted, letterSpacing = 1.sp)
                MiniSparkline(recent, accent, Modifier.weight(1f).padding(end = 10.dp).size(height = 26.dp, width = 1.dp))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onHunt) { Text("Hunt") }
                OutlinedButton(onClick = onChart) { Text(if (chartOpen) "Hide chart" else "Signal chart") }
                TextButton(onClick = onDetails) { Text("Details") }
            }
        }
    }
}

private fun LiveContact.toRowInfoSafe(stored: DeviceEntity?): Pair<String, String> {
    val row = toRowInfo(stored)
    return row.title to row.subtitle
}

/** "Nearby now": threats first, then strongest (5 dB buckets so rows don't jump on every packet). */
internal fun sortNearby(list: List<LiveContact>, stored: Map<String, DeviceEntity>): List<LiveContact> =
    list.sortedWith(
        compareByDescending<LiveContact> { it.isThreatNow(stored[it.id]) }
            .thenByDescending { Math.floorDiv(it.lastRssi, 5) }
            .thenBy { it.id },
    )

@Composable
internal fun NearbyHeading(count: Int, filterLabel: String?, onClearFilter: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        Text("NEARBY NOW · $count", fontSize = 10.sp, color = OkGreen, letterSpacing = 1.sp)
        if (filterLabel != null) {
            Surface(
                modifier = Modifier.clip(CircleShape).clickable(onClick = onClearFilter),
                color = RadarCyan.copy(alpha = 0.12f), shape = CircleShape, border = BorderStroke(1.dp, RadarCyan.copy(alpha = 0.5f)),
            ) { Text("Showing $filterLabel only  ✕", Modifier.padding(horizontal = 9.dp, vertical = 3.dp), fontSize = 10.sp, color = RadarCyan) }
        } else Text("threats first, then strongest", fontSize = 10.sp, color = ConsoleMuted, maxLines = 1)
    }
}


/** 0–4 bars from RSSI (≥-60, -70, -80, -90). */
internal fun signalBars(rssi: Int): Int = when {
    rssi >= -60 -> 4; rssi >= -70 -> 3; rssi >= -80 -> 2; rssi >= -90 -> 1; else -> 0
}

@Composable
internal fun SignalBars(rssi: Int, color: Color, modifier: Modifier = Modifier) {
    val bars = signalBars(rssi)
    androidx.compose.foundation.Canvas(modifier.size(width = 18.dp, height = 14.dp)) {
        val w = size.width / 4f
        for (i in 0 until 4) {
            val h = size.height * (i + 1) / 4f
            drawRoundRect(
                if (i < bars) color else color.copy(alpha = 0.18f),
                androidx.compose.ui.geometry.Offset(i * w + 1f, size.height - h),
                androidx.compose.ui.geometry.Size(w - 2.5f, h),
                androidx.compose.ui.geometry.CornerRadius(1.5f),
            )
        }
    }
}

/** Tiny line chart of recent RSSI readings. */
@Composable
internal fun MiniSparkline(samples: List<Int>, color: Color, modifier: Modifier = Modifier) {
    if (samples.size < 2) return
    androidx.compose.foundation.Canvas(modifier) {
        val lo = (samples.min() - 3).toFloat(); val hi = (samples.max() + 3).toFloat()
        val span = (hi - lo).coerceAtLeast(6f)
        val path = androidx.compose.ui.graphics.Path()
        samples.forEachIndexed { i, v ->
            val x = size.width * i / (samples.size - 1)
            val y = size.height - (v - lo) / span * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = androidx.compose.ui.graphics.drawscope.Stroke(2.2f))
        val lastY = size.height - (samples.last() - lo) / span * size.height
        drawCircle(color, 3.5f, androidx.compose.ui.geometry.Offset(size.width, lastY))
    }
}

/** One stat tile in the Radar console header. */
@Composable
private fun StatTile(value: String, label: String, color: Color, modifier: Modifier, onClick: (() -> Unit)? = null) {
    Surface(
        modifier = modifier.clip(RoundedCornerShape(10.dp)).let { if (onClick != null) it.clickable(onClick = onClick) else it },
        color = color.copy(alpha = 0.08f), shape = RoundedCornerShape(10.dp), border = BorderStroke(1.dp, color.copy(alpha = 0.28f)),
    ) {
        Column(Modifier.padding(horizontal = 8.dp, vertical = 7.dp)) {
            Text(value, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = color, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace)
            Text(label.uppercase(), fontSize = 8.5.sp, color = ConsoleMuted, letterSpacing = 0.8.sp, maxLines = 1)
        }
    }
}

/**
 * Radar console header: verdict, four stat tiles and a live scanner-health line, so it is obvious
 * at a glance whether anything needs attention and whether the radios are actually working.
 */
@Composable
internal fun RadarConsole(
    toCheck: Int,
    nearby: Int,
    located: Int,
    trackers: Int,
    health: com.rfwatch.app.repo.ScanHealth,
    bleState: com.rfwatch.app.scan.BleScanState,
    gpsAccuracyM: Double?,
    nowMs: Long,
    session: ScanSessionStatus,
    onRec: () -> Unit,
    onToCheck: () -> Unit,
    onTrackers: () -> Unit,
) {
    Card(
        Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = ConsolePanelRaised),
        border = BorderStroke(1.dp, (if (toCheck > 0) com.rfwatch.app.ui.theme.WarnAmber else RadarCyan).copy(alpha = 0.35f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        if (toCheck == 0) "● ALL CLEAR" else "● $toCheck WORTH A LOOK",
                        fontSize = 10.sp, letterSpacing = 1.5.sp, color = if (toCheck == 0) OkGreen else com.rfwatch.app.ui.theme.WarnAmber,
                    )
                    Text(if (toCheck == 0) "Nothing flagged right now" else "Check the flagged devices", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                }
                RecPill(session, onRec)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                StatTile("$nearby", "Nearby", RadarCyan, Modifier.weight(1f))
                StatTile("$located", "Located", com.rfwatch.app.ui.theme.SignalBlue, Modifier.weight(1f))
                StatTile("$trackers", "Trackers", if (trackers > 0) RadarPink else ConsoleMuted, Modifier.weight(1f), onTrackers)
                StatTile("$toCheck", "To check", if (toCheck > 0) com.rfwatch.app.ui.theme.WarnAmber else ConsoleMuted, Modifier.weight(1f), if (toCheck > 0) onToCheck else null)
            }
            // Scanner health — the BLE stall bug was invisible; now it isn't.
            val bleOk = bleState == com.rfwatch.app.scan.BleScanState.SCANNING && (health.updatedMs == 0L || health.blePerMin > 0)
            val bleText = when (bleState) {
                com.rfwatch.app.scan.BleScanState.BLUETOOTH_OFF -> "BLE off — turn Bluetooth on"
                com.rfwatch.app.scan.BleScanState.FAILED -> "BLE error — retrying"
                com.rfwatch.app.scan.BleScanState.STOPPED -> "BLE stopped"
                com.rfwatch.app.scan.BleScanState.SCANNING -> if (health.updatedMs == 0L) "BLE starting…"
                    else "BLE ${if (health.blePerMin >= 1000) "%.1fk".format(health.blePerMin / 1000.0) else "${health.blePerMin}"}/min"
            }
            val wifiAge = if (health.lastWifiScanMs == 0L) null else ((nowMs - health.lastWifiScanMs) / 1000).coerceAtLeast(0)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                HealthItem(bleOk, bleText)
                HealthItem(wifiAge != null && wifiAge < 180, if (wifiAge == null) "Wi-Fi …" else "Wi-Fi ${wifiAge}s")
                HealthItem(gpsAccuracyM != null && gpsAccuracyM <= 20, gpsAccuracyM?.let { "GPS ±${it.toInt()}m" } ?: "No GPS")
            }
        }
    }
}

@Composable
private fun HealthItem(ok: Boolean, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(7.dp)) { drawCircle(if (ok) OkGreen else com.rfwatch.app.ui.theme.WarnAmber) }
        Text(" $text", fontSize = 10.5.sp, color = Color(0xFFB7C6CD), fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace, maxLines = 1)
    }
}

/** "Nearby now" row: kind icon, name, signal bars, distance and trend; red edge for threats. */
@Composable
internal fun NearbyRow(contact: LiveContact, stored: DeviceEntity?, trend: RssiTrend, selected: Boolean, onClick: () -> Unit) {
    val info = contact.toRowInfo(stored)
    val threat = contact.isThreatNow(stored)
    val accent = if (threat) DangerRed else info.color
    Surface(
        modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        color = if (selected) Color(0xFF102A2A) else com.rfwatch.app.ui.theme.ConsolePanel,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) RadarCyan.copy(alpha = 0.7f) else Color(0xFF1B2833)),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(width = 3.dp, height = 46.dp).androidxBackground(if (threat) DangerRed else Color.Transparent),
            )
            Surface(Modifier.padding(start = 8.dp).size(30.dp), shape = CircleShape, color = accent.copy(alpha = 0.16f)) {
                androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) {
                    Icon(contact.icon(), null, tint = accent, modifier = Modifier.size(17.dp))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 10.dp, vertical = 7.dp)) {
                Text(info.title, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis)
                Text(info.subtitle, fontSize = 10.5.sp, color = accent.copy(alpha = 0.9f), maxLines = 1)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(end = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    trend.arrow()?.let { Text(it + " ", fontSize = 13.sp, color = if (it == "↑") RadarPink else ConsoleMuted, fontWeight = FontWeight.Bold) }
                    SignalBars(contact.lastRssi, accent)
                }
                Text(
                    "${contact.lastRssi} · ~${formatRssiDistance(rssiDistanceEstimateMeters(contact.lastRssi))} m",
                    fontSize = 10.sp, color = ConsoleMuted, fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                )
            }
        }
    }
}

private fun Modifier.androidxBackground(c: Color): Modifier = this.then(Modifier.background(c))
