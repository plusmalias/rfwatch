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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.WarnAmber
import com.rfwatch.core.rules.Threat

/** Room sweep (step 8): cameras/IoT, hidden SSIDs and strong source-less signals, each huntable. */
@Composable
fun RoomSweepScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier) {
    val findings by vm.room.collectAsState()
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("Room sweep", fontSize = 18.sp)
        Text(
            "Checking for surveillance: camera/IoT vendors, hidden networks and strong " +
                "signals with no visible source. Tap Hunt on any item to walk it down.",
            fontSize = 12.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(bottom = 8.dp),
        )
        if (findings.isEmpty()) {
            Text("No surveillance indicators yet. Keep Wi-Fi scanning active.", fontSize = 13.sp)
        }
        LazyColumn {
            items(findings) { f ->
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(Modifier.padding(10.dp)) {
                        Text(
                            f.reason.name.replace('_', ' '),
                            fontSize = 14.sp,
                            color = when (f.threat) {
                                Threat.PRIORITY -> DangerRed
                                Threat.NOTABLE -> WarnAmber
                                else -> Color.White
                            },
                        )
                        Text(f.detail, fontSize = 12.sp, color = Color(0xFF9CA3AF))
                        Text("${f.key} · ${f.rssi} dBm · Hunt available", fontSize = 11.sp, color = Color(0xFF6B7280))
                    }
                }
            }
        }
    }
}
