package com.rfwatch.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.RadarCyan
import com.rfwatch.app.ui.theme.RadarPink
import com.rfwatch.core.df.SweepResult
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Sweep mode (step 2). Guides the stationary body-shadowing sweep, then shows the polar
 * plot of binned RSSI with the fitted lobe and a bearing arrow. A bearing is only drawn
 * when the fit clears the confidence threshold; otherwise it reads "no clear direction".
 */
@Composable
fun SweepScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier) {
    val contacts by vm.contacts.collectAsState()
    val sweep by vm.sweep.collectAsState()
    var target by remember { mutableStateOf<String?>(null) }
    var sweeping by remember { mutableStateOf(false) }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("Sweep", fontSize = 18.sp)
        Text(
            "Hold the phone flat against your chest and turn slowly through 360° over " +
                "about 30 s. Your body shadows the signal, making it direction-dependent.",
            fontSize = 12.sp, color = Color(0xFF9CA3AF),
        )

        if (target == null) {
            Text("Pick a target:", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            LazyColumn {
                items(contacts.values.sortedByDescending { it.lastRssi }.toList()) { c ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(Modifier.fillMaxWidth().padding(10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                            Text("${c.label} (${c.lastRssi} dBm)", fontSize = 13.sp)
                            Button(onClick = { target = c.id }) { Text("Select") }
                        }
                    }
                }
            }
        } else {
            Text("Target: $target", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!sweeping) { vm.startSweep(target!!); sweeping = true }
                        else { vm.finishSweep(); sweeping = false }
                    }
                ) { Text(if (sweeping) "Finish sweep" else "Start sweep") }
                Button(onClick = { target = null; sweeping = false }) { Text("Change") }
            }
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp)) { drawPolar(sweep) }
            SweepReadout(sweep)
        }
    }
}

@Composable
private fun SweepReadout(sweep: SweepResult?) {
    if (sweep == null) { Text("Sweep to compute a bearing.", fontSize = 12.sp); return }
    val bearing = sweep.bearingDeg
    Text(
        if (bearing != null) "Bearing ${bearing.toInt()}° · confidence ${"%.2f".format(sweep.confidence)} · depth ${"%.1f".format(sweep.amplitudeDb)} dB"
        else "No clear direction (confidence ${"%.2f".format(sweep.confidence)}) — move closer or pick a BLE target",
        fontSize = 13.sp,
    )
}

private fun DrawScope.drawPolar(sweep: SweepResult?) {
    val cx = size.width / 2f; val cy = size.height / 2f
    val maxR = min(cx, cy) * 0.9f
    val grid = Color(0xFF123240)
    for (i in 1..3) drawCircle(grid, maxR * i / 3f, Offset(cx, cy), style = Stroke(1.5f))
    if (sweep == null || sweep.bins.isEmpty()) return

    val minV = sweep.bins.minOf { it.medianRssi }
    val maxV = sweep.bins.maxOf { it.medianRssi }
    val span = (maxV - minV).coerceAtLeast(1.0)
    // Binned medians.
    sweep.bins.forEach { b ->
        val r = (maxR * ((b.medianRssi - minV) / span)).toFloat().coerceIn(6f, maxR)
        val a = Math.toRadians(b.centerDeg)
        drawCircle(RadarCyan, 5f, Offset(cx + (r * sin(a)).toFloat(), cy - (r * cos(a)).toFloat()))
    }
    // Fitted lobe a + b·cos(θ−φ), sampled.
    sweep.bearingDeg?.let { phi ->
        val path = androidx.compose.ui.graphics.Path()
        var first = true
        var deg = 0.0
        while (deg <= 360.0) {
            val model = sweep.offsetDb + sweep.amplitudeDb * cos(Math.toRadians(deg - phi))
            val r = (maxR * ((model - minV) / span)).toFloat().coerceIn(2f, maxR)
            val a = Math.toRadians(deg)
            val p = Offset(cx + (r * sin(a)).toFloat(), cy - (r * cos(a)).toFloat())
            if (first) { path.moveTo(p.x, p.y); first = false } else path.lineTo(p.x, p.y)
            deg += 5.0
        }
        drawPath(path, RadarPink.copy(alpha = 0.7f), style = Stroke(3f))
        // Bearing arrow.
        val ar = Math.toRadians(phi)
        drawLine(RadarPink, Offset(cx, cy),
            Offset(cx + (maxR * sin(ar)).toFloat(), cy - (maxR * cos(ar)).toFloat()), strokeWidth = 4f)
    }
}
