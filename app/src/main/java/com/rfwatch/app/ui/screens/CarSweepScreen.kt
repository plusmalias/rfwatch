package com.rfwatch.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.data.DeviceEntity
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.OkGreen
import com.rfwatch.app.ui.theme.WarnAmber
import com.rfwatch.core.id.DeviceKind
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.text.DateFormat
import java.util.Date

private const val CAR_SWEEP_MS = 60_000L
private const val STRONG_RSSI = -70

/** One device heard during the car sweep, with the strongest reading and hit count. */
private data class CarHit(val id: String, val maxRssi: Int, val hits: Int)

private enum class CarConcern(val label: String) { TRACKER("Item tracker"), GPS("GPS tracker / telematics"), UNKNOWN_STRONG("Unidentified, very close") }

/**
 * Guided "check your car" sweep: walk around the vehicle for 60 s while RFWatch records the
 * strongest signal of every device, then ranks item trackers, GPS trackers and unidentified
 * strong signals that aren't marked as yours — each with full evidence and a Hunt shortcut.
 */
@Composable
fun CarSweepScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier, onHunt: (String) -> Unit) {
    val contacts by vm.contacts.collectAsState()
    val devices by vm.devices.collectAsState()
    val followers by vm.followers.collectAsState()
    val hits = remember { mutableStateMapOf<String, CarHit>() }
    var running by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var done by remember { mutableStateOf(false) }
    var logged by remember { mutableStateOf(false) }

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        val start = System.currentTimeMillis()
        coroutineScope {
            val job = launch {
                snapshotFlow { vm.contacts.value }.collect { map ->
                    map.values.filter { System.currentTimeMillis() - it.lastSeenMs < 5_000 }.forEach { c ->
                        val prev = hits[c.id]
                        hits[c.id] = CarHit(c.id, maxOf(prev?.maxRssi ?: -127, c.lastRssi), (prev?.hits ?: 0) + 1)
                    }
                }
            }
            while (System.currentTimeMillis() - start < CAR_SWEEP_MS) {
                progress = (System.currentTimeMillis() - start).toFloat() / CAR_SWEEP_MS
                delay(250)
            }
            job.cancel()
        }
        progress = 1f; running = false; done = true
    }

    val byId = devices.associateBy { it.id }
    val ranked = hits.values.mapNotNull { h ->
        val d = byId[h.id]
        val c = contacts[h.id]
        val kind = d?.effectiveKind() ?: c?.kind ?: DeviceKind.UNKNOWN
        if (d?.isOwnDevice == true || d?.isAllowlisted == true) return@mapNotNull null
        val concern = when {
            kind == DeviceKind.TRACKER || d?.trackerType != null || c?.trackerType != null -> CarConcern.TRACKER
            kind == DeviceKind.FLEET -> CarConcern.GPS
            h.maxRssi >= STRONG_RSSI && kind in setOf(DeviceKind.UNKNOWN, DeviceKind.HOTSPOT, DeviceKind.SMART_HOME) -> CarConcern.UNKNOWN_STRONG
            else -> null
        } ?: return@mapNotNull null
        Triple(h, d, concern)
    }.sortedWith(compareBy<Triple<CarHit, DeviceEntity?, CarConcern>> { it.third.ordinal }.thenByDescending { it.first.maxRssi })

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("VEHICLE CHECK", fontSize = 10.sp, color = WarnAmber, letterSpacing = 1.2.sp)
            Text("Check your car for trackers", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Start, then walk slowly around the car for a minute: along both sides, at the boot and bonnet, and lean in at the " +
                    "seats, dash and wheel arches. Trackers are strongest when you're right next to them. Mark your own devices " +
                    "(phone, key fob, car head unit) as Mine in Devices so they don't show here.",
                fontSize = 12.sp, color = ConsoleMuted,
            )
        }
        item {
            Button(enabled = !running, onClick = { hits.clear(); done = false; logged = false; progress = 0f; running = true }) {
                Text(if (running) "Walk around the car… ${((1 - progress) * CAR_SWEEP_MS / 1000).toInt()} s" else "Start 60-second car sweep")
            }
            if (running || done) LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (done) {
            item {
                LaunchedEffect(Unit) {
                    if (!logged) {
                        logged = true
                        vm.logCarSweep(ranked.map { (h, d, c) -> "${c.label}: ${d?.displayName ?: h.id} (${d?.brand ?: "?"}) max ${h.maxRssi} dBm" }, hits.size)
                    }
                }
                Text(
                    if (ranked.isEmpty()) "Nothing tracker-like among ${hits.size} signals heard. Note: many GPS trackers only use cellular and stay silent on Bluetooth/Wi-Fi — a physical check is still worth doing."
                    else "${ranked.size} device(s) to check, out of ${hits.size} heard. Use Hunt to walk to the strongest point.",
                    fontSize = 14.sp, color = if (ranked.any { it.third != CarConcern.UNKNOWN_STRONG }) DangerRed else if (ranked.isNotEmpty()) WarnAmber else OkGreen,
                )
            }
        }
        items(ranked, key = { it.first.id }) { (h, d, concern) ->
            val c = contacts[h.id]
            val color = if (concern == CarConcern.UNKNOWN_STRONG) WarnAmber else DangerRed
            val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
            Card(colors = CardDefaults.cardColors(containerColor = ConsolePanel), border = BorderStroke(1.dp, color.copy(alpha = 0.6f))) {
                Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(concern.label.uppercase(), color = color, fontSize = 12.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                        Text("max ${h.maxRssi} dBm · ${h.hits}×", fontSize = 12.sp, color = ConsoleMuted)
                    }
                    Text(d?.displayName ?: c?.label ?: h.id, fontSize = 15.sp)
                    Text(listOfNotNull(d?.brand ?: c?.brand, d?.model, d?.category ?: c?.category, d?.trackerType).distinct().joinToString(" · "), fontSize = 12.sp)
                    Text("${d?.band ?: c?.band} · ${h.id} · ~${formatRssiDistance(rssiDistanceEstimateMeters(h.maxRssi))} m at closest (RSSI guess)", fontSize = 11.sp, color = ConsoleMuted)
                    d?.let {
                        Text("First seen ${fmt.format(Date(it.firstSeenMs))} · heard ${it.seenCount}× in total · signal ${it.rssiMin}…${it.rssiMax} dBm", fontSize = 11.sp, color = ConsoleMuted)
                        it.radioDetails?.let { r -> Text(r, fontSize = 10.sp, color = ConsoleMuted, maxLines = 4) }
                    }
                    followers[h.id]?.let { f ->
                        Text("⚠ Also flagged as travelling with you: ${f.distinctLocations} places, ${"%.1f".format(f.maxSeparationM / 1000)} km apart", fontSize = 12.sp, color = DangerRed)
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                        Button(onClick = { onHunt(h.id) }) { Text("Hunt this one") }
                        OutlinedButton(onClick = { vm.setDeviceFlags(h.id, own = true) }) { Text("It's mine") }
                    }
                }
            }
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = ConsolePanel), border = BorderStroke(1.dp, ConsoleOutline)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("Where trackers are usually hidden", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    listOf(
                        "OBD-II port under the dash (plug-in GPS trackers)",
                        "Inside bumpers and behind number plates; wheel arches and behind mud flaps",
                        "Magnetic boxes on the chassis rails, tow bar or behind the fuel tank",
                        "Under seats, seat pockets, glovebox, centre console, sun visors",
                        "Boot: spare-wheel well, side panels, under the boot liner",
                        "Item trackers (AirTag etc.) sewn into or taped under seat fabric or inside bags/coats",
                    ).forEach { Text("• $it", fontSize = 12.sp, color = ConsoleMuted) }
                    Text("If you find one: photograph it in place before touching it, note the time, and consider contacting police before removing it.", fontSize = 12.sp, color = WarnAmber)
                }
            }
        }
    }
}
