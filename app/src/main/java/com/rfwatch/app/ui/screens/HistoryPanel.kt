package com.rfwatch.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.data.EventEntity
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.core.geo.haversineM
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Event kinds plotted on the history map, with their colour and plain-English name. */
internal val HISTORY_KINDS: Map<String, Pair<String, String>> = linkedMapOf(
    "LAW_ENCOUNTER" to ("#FF3B4E" to "Police equipment"),
    "FOLLOWER" to ("#FF7A1A" to "Travelling with you"),
    "DRONE" to ("#E040FB" to "Drone"),
    "NEW_AT_HOME" to ("#FFB020" to "New device at home"),
    "EMERGENCY" to ("#3B82F6" to "Ambulance / fire"),
    "ATTACK_TOOL" to ("#22D3EE" to "Hacking tool"),
    "BLE_SPAM" to ("#22D3EE" to "Bluetooth spam"),
    "EVIL_TWIN" to ("#22D3EE" to "Fake Wi-Fi"),
    "IMSI_CATCHER" to ("#FF3B4E" to "Cell-site simulator"),
    "SKIMMER_CHECK" to ("#94A3B8" to "Skimmer check"),
    "CAR_SWEEP" to ("#94A3B8" to "Car check"),
    "LOCATE" to ("#34D399" to "Located device"),
)

internal fun EventEntity.isMappableHistory() = lat != null && lon != null && kind in HISTORY_KINDS

private val dateFmt = SimpleDateFormat("EEE d MMM HH:mm", Locale.getDefault())

/** Places (≤ [radiusM] apart) where two or more surveillance-type events happened. */
internal fun repeatPlaces(events: List<EventEntity>, radiusM: Double = 150.0): List<List<EventEntity>> {
    val clusters = ArrayList<MutableList<EventEntity>>()
    events.filter { it.isMappableHistory() && it.kind !in setOf("SKIMMER_CHECK", "CAR_SWEEP", "LOCATE") }
        .sortedBy { it.timestampMs }
        .forEach { e ->
            val c = clusters.firstOrNull { cl -> haversineM(cl.first().lat!!, cl.first().lon!!, e.lat!!, e.lon!!) <= radiusM }
            if (c != null) c += e else clusters += mutableListOf(e)
        }
    return clusters.filter { it.size >= 2 }.sortedByDescending { it.size }
}

/**
 * History panel under the map: the selected event in full, "repeat places" where events cluster
 * (the pattern that matters — e.g. the same police router outside your house three nights
 * running), and a recent timeline. Tapping a row focuses it.
 */
@Composable
internal fun HistoryPanel(events: List<EventEntity>, selected: EventEntity?, onSelect: (EventEntity) -> Unit) {
    val mappable = events.filter { it.isMappableHistory() }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        selected?.let { e ->
            Card(colors = CardDefaults.cardColors(containerColor = ConsolePanel), border = BorderStroke(1.dp, Color(android.graphics.Color.parseColor(HISTORY_KINDS[e.kind]?.first ?: "#94A3B8")))) {
                Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${HISTORY_KINDS[e.kind]?.second ?: e.kind} · ${dateFmt.format(Date(e.timestampMs))}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Text(e.detail, fontSize = 12.sp)
                    Text("At %.5f, %.5f".format(Locale.US, e.lat, e.lon) + (e.deviceId?.let { " · device $it" } ?: ""), fontSize = 10.sp, color = ConsoleMuted)
                    val nearby = mappable.count { it.rowId != e.rowId && haversineM(it.lat!!, it.lon!!, e.lat!!, e.lon!!) <= 150.0 }
                    if (nearby > 0) Text("$nearby other event(s) within 150 m of here", fontSize = 11.sp, color = Color(0xFFFFB020))
                }
            }
        }
        val repeats = repeatPlaces(events)
        Text(
            if (repeats.isEmpty()) "REPEAT PLACES · none yet" else "REPEAT PLACES · ${repeats.size} spot(s) with 2+ events",
            fontSize = 10.sp, color = ConsoleMuted, letterSpacing = 1.sp,
        )
        repeats.take(6).forEach { cl ->
            val kinds = cl.groupingBy { HISTORY_KINDS[it.kind]?.second ?: it.kind }.eachCount().entries.joinToString { "${it.key} ×${it.value}" }
            Card(
                Modifier.fillMaxWidth().clickable { onSelect(cl.last()) },
                colors = CardDefaults.cardColors(containerColor = ConsolePanel), border = BorderStroke(1.dp, ConsoleOutline),
            ) {
                Column(Modifier.padding(8.dp)) {
                    Text("${cl.size} events here: $kinds", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                    Text(cl.takeLast(6).joinToString(" · ") { dateFmt.format(Date(it.timestampMs)) }, fontSize = 11.sp, color = ConsoleMuted)
                }
            }
        }
        Text("TIMELINE · ${mappable.size} mapped events", fontSize = 10.sp, color = ConsoleMuted, letterSpacing = 1.sp)
        mappable.sortedByDescending { it.timestampMs }.take(15).forEach { e ->
            Text(
                "${dateFmt.format(Date(e.timestampMs))} · ${HISTORY_KINDS[e.kind]?.second} — ${e.detail.take(110)}",
                fontSize = 11.sp, modifier = Modifier.fillMaxWidth().clickable { onSelect(e) }.padding(vertical = 2.dp),
            )
        }
    }
}
