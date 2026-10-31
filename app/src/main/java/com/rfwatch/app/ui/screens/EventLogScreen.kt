package com.rfwatch.app.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.WarnAmber
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Event log + alerts timeline, filterable and exportable as JSON (step 10). */
@Composable
fun EventLogScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier) {
    val events by vm.events.collectAsState()
    val alerts by vm.alerts.collectAsState()
    val context = LocalContext.current
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm:ss", Locale.getDefault()) }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("ACTIVITY / ALERTS", fontSize = 10.sp, color = WarnAmber, letterSpacing = 1.2.sp)
                Text("Event log", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = { vm.exportEvidence(context, null) { context.startActivity(it) } }) { Text("Report") }
                androidx.compose.material3.OutlinedButton(onClick = { exportJson(context, events, alerts) }) { Text("JSON") }
            }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            items(alerts, key = { "a${it.rowId}" }) { a ->
                Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = ConsolePanel), border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleOutline)) {
                    Column(Modifier.padding(8.dp)) {
                        val color = when (a.type) {
                            "AXON_SIGNATURE", "LAW_ENFORCEMENT_SIGNATURE", "PRIORITY_NEARBY" -> DangerRed
                            "TRACKER_FOLLOWING" -> com.rfwatch.app.ui.theme.RadarPink
                            "IMSI_CATCHER", "CAMERA_IOT_SIGNATURE" -> WarnAmber
                            else -> Color(0xFF9CA3AF)
                        }
                        Text("⚠ ${a.type}", fontSize = 13.sp, color = color)
                        Text("${fmt.format(Date(a.timestampMs))} · ${a.message}", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                    }
                }
            }
            items(events, key = { "e${it.rowId}" }) { e ->
                Card(Modifier.fillMaxWidth(), colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = ConsolePanel), border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleOutline)) {
                    Column(Modifier.padding(8.dp)) {
                        Text(e.kind, fontSize = 13.sp)
                        Text("${fmt.format(Date(e.timestampMs))} · ${e.detail}", fontSize = 11.sp, color = Color(0xFF9CA3AF))
                    }
                }
            }
        }
    }
}

private fun exportJson(
    context: Context,
    events: List<com.rfwatch.app.data.EventEntity>,
    alerts: List<com.rfwatch.app.data.AlertEntity>,
) {
    val sb = StringBuilder("{\n  \"alerts\": [\n")
    alerts.forEachIndexed { i, a ->
        sb.append("    {\"type\":\"${a.type}\",\"ts\":${a.timestampMs},\"device\":${jsonStr(a.deviceId)},\"message\":${jsonStr(a.message)},\"bearing\":${a.bearingDeg},\"confidence\":${a.confidence}}")
        sb.append(if (i < alerts.size - 1) ",\n" else "\n")
    }
    sb.append("  ],\n  \"events\": [\n")
    events.forEachIndexed { i, e ->
        sb.append("    {\"kind\":\"${e.kind}\",\"ts\":${e.timestampMs},\"device\":${jsonStr(e.deviceId)},\"detail\":${jsonStr(e.detail)},\"lat\":${e.lat},\"lon\":${e.lon}}")
        sb.append(if (i < events.size - 1) ",\n" else "\n")
    }
    sb.append("  ]\n}")
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    cm.setPrimaryClip(ClipData.newPlainText("RFWatch export", sb.toString()))
}

private fun jsonStr(s: String?): String = if (s == null) "null" else "\"" + s.replace("\"", "\\\"") + "\""
