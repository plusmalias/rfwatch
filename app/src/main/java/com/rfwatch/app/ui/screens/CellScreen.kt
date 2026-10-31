package com.rfwatch.app.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.OkGreen
import com.rfwatch.app.ui.theme.WarnAmber
import com.rfwatch.core.cs.cell.CellThreatLevel

/**
 * Cell / IMSI-catcher ("stingray") monitor. Shows the serving cell, the current heuristic
 * threat level and the reasons behind it — with an explicit honesty banner about the hard
 * limits of non-rooted detection.
 */
@Composable
fun CellScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier) {
    val assessment by vm.cell.collectAsState()

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("Cell / IMSI-catcher monitor", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
        Text(
            "Heuristic only. A non-rooted phone cannot read the baseband, so this watches " +
                "the serving cell, its neighbours, radio type and signal. Roaming, a new " +
                "tower or a real 2G fallback can trip it; a careful catcher can hide. HIGH " +
                "means \"worth checking\", not proof.",
            fontSize = 11.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(vertical = 8.dp),
        )

        val a = assessment
        if (a == null) {
            Text("Waiting for cell data… (needs phone + location permission)", fontSize = 13.sp)
            return@Column
        }

        val (label, color) = when (a.level) {
            CellThreatLevel.HIGH -> "HIGH" to DangerRed
            CellThreatLevel.MEDIUM -> "MEDIUM" to WarnAmber
            CellThreatLevel.LOW -> "LOW" to WarnAmber
            CellThreatLevel.NONE -> if (a.learning) "LEARNING" to Color(0xFF9CA3AF) else "CLEAR" to OkGreen
        }
        Card(Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(Modifier.padding(12.dp)) {
                Text(label, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = color)
                Text(a.servingSummary, fontSize = 12.sp, color = Color(0xFF9CA3AF))
                if (a.learning) Text("Learning the normal cells in this area…", fontSize = 11.sp, color = Color(0xFF6B7280))
            }
        }

        if (a.findings.isNotEmpty()) {
            Text("Reasons", fontSize = 14.sp, modifier = Modifier.padding(bottom = 4.dp))
            LazyColumn {
                items(a.findings) { f ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 3.dp)) {
                        Column(Modifier.padding(10.dp)) {
                            Text(f.type.name.replace('_', ' '), fontSize = 13.sp)
                            Text(f.detail, fontSize = 11.sp, color = Color(0xFF9CA3AF))
                        }
                    }
                }
            }
        }
    }
}
