package com.rfwatch.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.CreditCard
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.data.EventEntity
import com.rfwatch.app.discreet.Discreet
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.OkGreen
import com.rfwatch.app.ui.theme.RadarCyan
import com.rfwatch.app.ui.theme.WarnAmber
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Destinations the hub can open (routed to tabs by MainActivity). */
enum class HubDest { STALKERWARE, CAMERA_FINDER, ROOM, SKIMMER, CAR, CELL, LOG, SETTINGS, ULTRASONIC }

private data class HubTool(val dest: HubDest, val title: String, val icon: ImageVector, val status: String, val tone: Color)

/**
 * "Protect" hub (More tab): every protective tool grouped by what you're checking, each with a
 * live one-line status drawn from the event log and current state, so you can see at a glance
 * what has been checked, when, and with what result.
 */
@Composable
fun ProtectHub(vm: RfWatchViewModel, modifier: Modifier = Modifier, open: (HubDest) -> Unit) {
    val context = LocalContext.current
    val events by vm.events.collectAsState()
    val home by vm.homeStatus.collectAsState()
    val cell by vm.cell.collectAsState()
    val fmt = SimpleDateFormat("EEE HH:mm", Locale.getDefault())
    fun last(kind: String): EventEntity? = events.filter { it.kind == kind }.maxByOrNull { it.timestampMs }
    fun ago(e: EventEntity) = fmt.format(Date(e.timestampMs))

    val stalker = last("STALKERWARE_SCAN")
    val stalkerFlagged = stalker?.detail?.let { Regex("(\\d+) critical/high").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
    val skimmer = last("SKIMMER_CHECK")
    val skimmerHigh = skimmer?.detail?.let { Regex("(\\d+) high").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
    val ultra = last("ULTRASONIC_CHECK")
    val ultraBeacon = ultra?.detail?.contains("Beacon-like ultrasonic signal found") == true
    val ultraSteady = ultra?.detail?.contains("Steady ultrasonic tone") == true
    val car = last("CAR_SWEEP")
    val carFindings = car?.detail?.let { Regex("(\\d+) to check").find(it)?.groupValues?.get(1)?.toIntOrNull() } ?: 0
    val historyCount = events.count { it.isMappableHistory() }
    val disguised = Discreet.isDisguised(context)
    val locked = Discreet.lockEnabled(context)

    val groups = listOf(
        "Check my phone" to listOf(
            HubTool(
                HubDest.STALKERWARE, "Stalkerware & spyware check", Icons.Filled.PhoneAndroid,
                stalker?.let { if (stalkerFlagged > 0) "Last scan ${ago(it)}: $stalkerFlagged app(s) flagged — review" else "Last scan ${ago(it)}: clean" } ?: "Never scanned — tap to scan",
                when { stalker == null -> WarnAmber; stalkerFlagged > 0 -> DangerRed; else -> OkGreen },
            ),
            HubTool(
                HubDest.SETTINGS, "Discreet mode", Icons.Filled.VisibilityOff,
                when { disguised -> "On — disguised as Calculator"; locked -> "PIN / phone lock on, not disguised"; else -> "Off — set a PIN and disguise in Settings" },
                if (disguised || locked) OkGreen else ConsoleMuted,
            ),
        ),
        "Check a place" to listOf(
            HubTool(HubDest.CAMERA_FINDER, "Hidden camera finder", Icons.Filled.Videocam, "IR LEDs and lens glints with the phone camera", RadarCyan),
            HubTool(HubDest.ROOM, "Room sweep", Icons.Filled.Wifi, "Wi-Fi / Bluetooth camera and IoT signatures nearby", RadarCyan),
            HubTool(
                HubDest.SKIMMER, "Skimmer check", Icons.Filled.CreditCard,
                skimmer?.let { if (skimmerHigh > 0) "Last check ${ago(it)}: $skimmerHigh HIGH — don't use that terminal" else "Last check ${ago(it)}: nothing skimmer-like" } ?: "Run at a fuel pump or ATM",
                when { skimmer == null -> RadarCyan; skimmerHigh > 0 -> DangerRed; else -> OkGreen },
            ),
            HubTool(
                HubDest.ULTRASONIC, "Ultrasonic beacon check", Icons.Filled.GraphicEq,
                ultra?.let { "Last check ${ago(it)}: " + when { ultraBeacon -> "beacon-like tone found"; ultraSteady -> "steady tone only (electronics?)"; else -> "nothing beacon-like" } }
                    ?: "Hidden high-pitched tracking tones · 20 s listen",
                when { ultra == null -> RadarCyan; ultraBeacon -> DangerRed; ultraSteady -> WarnAmber; else -> OkGreen },
            ),
            HubTool(
                HubDest.SETTINGS, "Home watch", Icons.Filled.Home,
                when { !home.isSet -> "Not set — Set Home in Settings"; !home.mature -> "Learning ${home.visitsLearned}/${com.rfwatch.core.cs.Baseline.MIN_SESSIONS} visits" + if (home.inside) " · you're home" else ""; else -> "Watching" + if (home.inside) " · you're home" else "" },
                when { !home.isSet -> WarnAmber; home.mature -> OkGreen; else -> RadarCyan },
            ),
        ),
        "Check my car" to listOf(
            HubTool(
                HubDest.CAR, "Car tracker sweep", Icons.Filled.DirectionsCar,
                car?.let { if (carFindings > 0) "Last sweep ${ago(it)}: $carFindings to check" else "Last sweep ${ago(it)}: nothing tracker-like" } ?: "Never checked — 60 s walk-around",
                when { car == null -> WarnAmber; carFindings > 0 -> DangerRed; else -> OkGreen },
            ),
        ),
        "Out & about" to listOf(
            HubTool(
                HubDest.CELL, "Cellular / fake tower checks", Icons.Filled.CellTower,
                cell?.let { "Now: ${it.level} · ${it.servingSummary}" } ?: "Waiting for cell info",
                when (cell?.level?.name) { "HIGH" -> DangerRed; "MEDIUM" -> WarnAmber; null -> ConsoleMuted; else -> OkGreen },
            ),
            HubTool(HubDest.LOG, "Event log & evidence report", Icons.Filled.History, "$historyCount mapped events · export a report for police / support services", RadarCyan),
        ),
        "App" to listOf(
            HubTool(HubDest.SETTINGS, "Settings", Icons.Filled.Settings, "Alerts, flash, police / ambulance toggles, home, discreet mode, data", ConsoleMuted),
        ),
    )

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("PROTECT", fontSize = 10.sp, color = RadarCyan, letterSpacing = 1.4.sp)
            Text("Checks & tools", fontSize = 21.sp, fontWeight = FontWeight.SemiBold)
            Text("Everything runs on this phone. Status lines show your latest result for each check.", fontSize = 12.sp, color = ConsoleMuted)
        }
        groups.forEach { (title, tools) ->
            item(key = "h-$title") { Text(title.uppercase(), fontSize = 11.sp, color = ConsoleMuted, letterSpacing = 1.2.sp, modifier = Modifier.padding(top = 8.dp)) }
            tools.forEach { t -> item(key = "t-$title-${t.title}") { HubCard(t) { open(t.dest) } } }
        }
    }
}

@Composable
private fun HubCard(t: HubTool, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ConsolePanel),
        border = BorderStroke(1.dp, ConsoleOutline),
    ) {
        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(40.dp).background(t.tone.copy(alpha = 0.14f), RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) { Icon(t.icon, contentDescription = null, tint = t.tone) }
            Column(Modifier.weight(1f)) {
                Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                Text(t.status, fontSize = 12.sp, color = if (t.tone == ConsoleMuted) ConsoleMuted else t.tone, maxLines = 2)
            }
        }
    }
}
