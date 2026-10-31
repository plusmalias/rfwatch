package com.rfwatch.app.ui.screens

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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.ui.RfWatchViewModel

/** Full device inventory from the (encrypted) database, newest-seen first. */
@Composable
fun DevicesScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier, onRoom: () -> Unit = {}) {
    val devices by vm.devices.collectAsState()
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            Text("Devices (${devices.size})", fontSize = 18.sp)
            Button(onClick = onRoom) { Text("Room sweep") }
        }
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(devices, key = { it.id }) { d ->
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(10.dp)) {
                        Text(d.displayName ?: d.id, fontSize = 14.sp)
                        Text(
                            "${d.band} · ${d.classification}" +
                                (d.trackerType?.let { " · $it" } ?: "") +
                                " · ${d.lastRssi} dBm" +
                                (if (d.isOwnDevice) " · own" else "") +
                                (if (d.isAllowlisted) " · allowlisted" else ""),
                            fontSize = 12.sp, color = Color(0xFF9CA3AF),
                        )
                    }
                }
            }
        }
    }
}
