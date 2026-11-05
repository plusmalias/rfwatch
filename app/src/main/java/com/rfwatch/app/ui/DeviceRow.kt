package com.rfwatch.app.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.data.DeviceEntity
import com.rfwatch.app.repo.LiveContact
import com.rfwatch.app.ui.screens.deviceCategoryColor
import com.rfwatch.app.ui.screens.effectiveKind
import com.rfwatch.app.ui.screens.formatRssiDistance
import com.rfwatch.app.ui.screens.rssiDistanceEstimateMeters
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.RadarCyan
import com.rfwatch.app.ui.theme.WarnAmber
import com.rfwatch.core.id.DeviceKind

/** Short noun for a kind, used in generated names ("Apple tracker ·73:47"). */
internal fun DeviceKind.shortName(): String = when (this) {
    DeviceKind.TRACKER -> "tracker"
    DeviceKind.DRONE -> "drone"
    DeviceKind.LAW_ENFORCEMENT -> "Axon / law device"
    DeviceKind.EMERGENCY -> "emergency device"
    DeviceKind.ATTACK_TOOL -> "hacking tool"
    DeviceKind.CAMERA_IOT -> "camera / IoT"
    DeviceKind.FLEET -> "GPS tracker"
    DeviceKind.VEHICLE -> "vehicle"
    DeviceKind.PHONE -> "phone"
    DeviceKind.COMPUTER -> "computer"
    DeviceKind.AUDIO -> "audio"
    DeviceKind.WEARABLE -> "wearable"
    DeviceKind.TV_MEDIA -> "TV / media"
    DeviceKind.HEALTH -> "fitness device"
    DeviceKind.INPUT -> "keyboard / controller"
    DeviceKind.SMART_HOME -> "smart-home device"
    DeviceKind.BEACON -> "beacon"
    DeviceKind.HOTSPOT -> "hotspot"
    DeviceKind.NETWORK -> "Wi-Fi network"
    DeviceKind.UNKNOWN -> "device"
}

/**
 * The name shown for a device everywhere: your nickname, else what it advertises, else a
 * readable "Brand kind ·last two address bytes". Display only — the id stays the MAC.
 */
fun friendlyName(
    nickname: String?, advertised: String?, brand: String?, kind: DeviceKind, id: String, model: String? = null,
): String {
    nickname?.trim()?.takeIf { it.isNotEmpty() }?.let { return it }
    advertised?.trim()?.takeIf { it.isNotEmpty() && it != id }?.let { return it }
    val tail = id.takeLast(5).uppercase()
    val who = brand?.takeIf { it.isNotBlank() }
    // A model decoded from the payload ("AirPods Pro (2nd gen)", "Galaxy Watch5") beats "Apple audio".
    model?.trim()?.takeIf { it.isNotEmpty() }?.let { m ->
        val named = if (who == null || m.contains(who, ignoreCase = true)) m else "$who $m"
        return "$named ·$tail"
    }
    return "${who ?: "Unknown"} ${kind.shortName()} ·$tail"
}

/** "now", "4 min ago", "2 h ago", "3 d ago". */
fun agoText(nowMs: Long, thenMs: Long): String {
    val s = ((nowMs - thenMs) / 1000).coerceAtLeast(0)
    return when {
        s < 90 -> "now"
        s < 3600 -> "${s / 60} min ago"
        s < 86_400 -> "${s / 3600} h ago"
        else -> "${s / 86_400} d ago"
    }
}

/** Everything one compact row shows; built from either a stored device or a live contact. */
data class DeviceRowInfo(
    val id: String,
    val title: String,
    val subtitle: String,
    val color: Color,
    val rssi: Int,
    val meta: String?,
    val badge: String? = null,
    val badgeColor: Color = ConsoleMuted,
)

private fun badgeFor(own: Boolean, trusted: Boolean, threat: Boolean): Pair<String, Color>? = when {
    own -> "MINE" to RadarCyan
    trusted -> "TRUSTED" to RadarCyan
    threat -> "CHECK" to WarnAmber
    else -> null
}

fun DeviceEntity.toRowInfo(nowMs: Long): DeviceRowInfo {
    val kind = effectiveKind()
    val badge = badgeFor(isOwnDevice, isAllowlisted, isThreat(this, kind))
    return DeviceRowInfo(
        id = id,
        title = friendlyName(nickname, displayName, brand, kind, id, model),
        subtitle = rowSubtitle(friendlyName(nickname, displayName, brand, kind, id, model), brand, model, kind, band, seenCount),
        color = deviceCategoryColor(category, classification),
        rssi = lastRssi,
        meta = agoText(nowMs, lastSeenMs),
        badge = badge?.first, badgeColor = badge?.second ?: ConsoleMuted,
    )
}

/** Second row line: only what the title does not already say, then band and how often heard. */
internal fun rowSubtitle(title: String, brand: String?, model: String?, kind: DeviceKind, band: String, seen: Int): String =
    listOfNotNull(
        brand?.takeIf { !title.contains(it, ignoreCase = true) },
        model?.takeIf { !title.contains(it, ignoreCase = true) },
        kind.label.takeIf { !title.contains(it, ignoreCase = true) && (model == null || !model.contains(it, ignoreCase = true)) },
        if (band == "WIFI") "Wi-Fi" else "BLE",
        seen.takeIf { it > 1 }?.let { "heard ${it}×" },
    ).distinct().joinToString(" · ")

/** Live radar contact, enriched with the stored row (nickname, model, flags) when there is one. */
fun LiveContact.toRowInfo(stored: DeviceEntity?): DeviceRowInfo {
    val kindNow = if (kind != DeviceKind.UNKNOWN) kind else stored?.effectiveKind() ?: DeviceKind.UNKNOWN
    val badge = stored?.let { badgeFor(it.isOwnDevice, it.isAllowlisted, isThreat(it, kindNow)) }
    return DeviceRowInfo(
        id = id,
        title = friendlyName(stored?.nickname, label.takeIf { it != id }, brand ?: stored?.brand, kindNow, id, stored?.model),
        subtitle = rowSubtitle(
            friendlyName(stored?.nickname, label.takeIf { it != id }, brand ?: stored?.brand, kindNow, id, stored?.model),
            brand ?: stored?.brand, stored?.model, kindNow, band.name, stored?.seenCount ?: 0,
        ),
        color = deviceCategoryColor(category, classification),
        rssi = lastRssi,
        meta = "~${formatRssiDistance(rssiDistanceEstimateMeters(lastRssi))} m",
        badge = badge?.first, badgeColor = badge?.second ?: ConsoleMuted,
    )
}

/** The one compact device row used by Radar, Find and Devices. */
@Composable
fun DeviceRowCompact(info: DeviceRowInfo, selected: Boolean = false, onClick: () -> Unit) {
    Surface(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).clickable(onClick = onClick),
        color = if (selected) Color(0xFF102A2A) else ConsolePanel,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, if (selected) RadarCyan.copy(alpha = 0.7f) else ConsoleOutline),
    ) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.padding(end = 10.dp).size(10.dp).clip(CircleShape).background(info.color))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(info.title, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
                        overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    info.badge?.let { Text(it, fontSize = 9.sp, color = info.badgeColor, letterSpacing = 0.8.sp) }
                }
                Text(info.subtitle, fontSize = 11.sp, color = info.color.copy(alpha = 0.9f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Column(horizontalAlignment = Alignment.End, modifier = Modifier.padding(start = 8.dp)) {
                Text("${info.rssi} dBm", fontSize = 12.sp)
                info.meta?.let { Text(it, fontSize = 10.sp, color = ConsoleMuted) }
            }
        }
    }
}
