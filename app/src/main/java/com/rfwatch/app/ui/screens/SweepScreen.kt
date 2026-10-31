package com.rfwatch.app.ui.screens

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
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
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.repo.LiveContact
import com.rfwatch.app.repo.RfWatchRepository
import com.rfwatch.app.repo.SweepLive
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.WarnAmber
import com.rfwatch.core.model.Band
import kotlinx.coroutines.delay
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
 *
 * Two capture modes:
 *  - Continuous (needs a compass): turn slowly; each advert is tagged with the live heading.
 *  - Guided steps (any phone): the phone buzzes every [GUIDED_STEP_MS]; the user turns 45°
 *    clockwise at each buzz, and samples are tagged with the step angle. The bearing is then
 *    relative to the starting direction. Phones without a magnetometer (e.g. ZTE P660) can
 *    only use this mode.
 */
@Composable
fun SweepScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier) {
    val contacts by vm.contacts.collectAsState()
    val sweep by vm.sweep.collectAsState()
    val live by vm.sweepLive.collectAsState()
    val context = LocalContext.current
    val hasCompass = vm.hasCompass
    var target by remember { mutableStateOf<String?>(null) }
    var sweeping by remember { mutableStateOf(false) }
    var guided by remember { mutableStateOf(!hasCompass) }
    var finishedGuided by remember { mutableStateOf(false) }
    var step by remember { mutableIntStateOf(0) }
    val targetContact = target?.let { contacts[it] }

    // Guided step sequencer: buzz, set the step angle, dwell, repeat; then fit.
    LaunchedEffect(sweeping, guided) {
        if (!sweeping || !guided) return@LaunchedEffect
        for (i in 0 until GUIDED_STEPS) {
            step = i
            vm.setGuidedHeading(i * GUIDED_STEP_DEG)
            buzz(context, if (i == 0) 300L else 120L)
            delay(GUIDED_STEP_MS)
        }
        buzz(context, 500L)
        vm.finishSweep()
        finishedGuided = true
        sweeping = false
    }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("Sweep", fontSize = 18.sp)
        if (!hasCompass) {
            Text(
                "This phone has no compass (magnetometer), so the sweep uses guided steps: " +
                    "the bearing is relative to the direction you start facing.",
                fontSize = 12.sp, color = WarnAmber,
            )
        } else {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = !guided, enabled = !sweeping, onClick = { guided = false }, label = { Text("Continuous") })
                FilterChip(selected = guided, enabled = !sweeping, onClick = { guided = true }, label = { Text("Guided steps") })
            }
        }
        Text(
            if (guided) "Hold the phone flat against your chest and face a landmark. Start, then turn " +
                "45° to your right at every buzz (${GUIDED_STEPS} steps, ~${GUIDED_STEPS * GUIDED_STEP_MS / 1000} s). " +
                "Your body shadows the signal, making it direction-dependent."
            else "Hold the phone flat against your chest and turn slowly through 360° over " +
                "about 30 s. Your body shadows the signal, making it direction-dependent.",
            fontSize = 12.sp, color = Color(0xFF9CA3AF),
        )

        if (target == null) {
            Text("Pick a target (Bluetooth works best — Wi-Fi is only scanned every ~30 s):", fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp))
            val candidates = contacts.values.sortedWith(compareBy<LiveContact> { it.band != Band.BLE }.thenByDescending { it.lastRssi })
            LazyColumn {
                items(candidates, key = { it.id }) { c ->
                    Card(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(10.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text(c.label, fontSize = 13.sp)
                                Text(
                                    "${c.band} · ${c.brand ?: "unknown brand"} · ${c.category ?: "device"} · ${c.lastRssi} dBm",
                                    fontSize = 11.sp, color = deviceCategoryColor(c.category, c.classification),
                                )
                            }
                            Button(onClick = { target = c.id; finishedGuided = false }) { Text("Select") }
                        }
                    }
                }
            }
        } else {
            Text(
                "Target: ${targetContact?.label ?: target} · ${targetContact?.band ?: ""} · now ${targetContact?.lastRssi ?: "?"} dBm",
                fontSize = 13.sp, modifier = Modifier.padding(top = 8.dp),
            )
            if (targetContact?.band == Band.WIFI) {
                Text("Wi-Fi targets update only every ~30 s — expect too few samples. Prefer a Bluetooth device.", fontSize = 11.sp, color = WarnAmber)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = {
                        if (!sweeping) {
                            vm.startSweep(target!!, guided); sweeping = true; finishedGuided = false; step = 0
                        } else {
                            vm.finishSweep(); sweeping = false
                        }
                    }
                ) { Text(if (!sweeping) "Start sweep" else if (guided) "Stop" else "Finish sweep") }
                Button(enabled = !sweeping, onClick = { target = null }) { Text("Change") }
            }
            if (sweeping) {
                val l = live
                Text(
                    (if (guided) "Step ${step + 1}/$GUIDED_STEPS — face ${(step * GUIDED_STEP_DEG).toInt()}° right of start · " else "") +
                        "samples ${l?.samples ?: 0}" + (if (guided) " (this step ${l?.stepSamples ?: 0})" else "") +
                        " · last ${l?.lastRssi?.let { "$it dBm" } ?: "—"}",
                    fontSize = 13.sp, color = RadarCyan,
                )
                if (!guided && l != null && !l.headingAvailable) {
                    Text("No compass heading yet — move the phone in a figure-8 to calibrate, or use guided steps.", fontSize = 11.sp, color = WarnAmber)
                }
            }
            Canvas(Modifier.fillMaxWidth().aspectRatio(1f).padding(12.dp)) { drawPolar(sweep) }
            if (!sweeping) SweepReadout(sweep, live, relative = guided || finishedGuided)
        }
    }
}

private const val GUIDED_STEPS = 8
private const val GUIDED_STEP_DEG = 45.0
private const val GUIDED_STEP_MS = 5_000L

private fun buzz(context: Context, ms: Long) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
    } else {
        @Suppress("DEPRECATION") context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
    }
    runCatching { vibrator.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) }
}

@Composable
private fun SweepReadout(sweep: SweepResult?, live: SweepLive?, relative: Boolean) {
    if (sweep == null) {
        val samples = live?.samples
        Text(
            if (samples == null) "Sweep to compute a bearing."
            else "Not enough data: $samples usable samples from the target (need ${RfWatchRepository.MIN_SWEEP_SAMPLES}). " +
                "Move closer or pick a Bluetooth device that is advertising often.",
            fontSize = 12.sp,
        )
        return
    }
    val bearing = sweep.bearingDeg
    val where = bearing?.let { if (relative) "${it.toInt()}° clockwise from your start direction" else "bearing ${it.toInt()}° (true)" }
    Text(
        if (where != null) "Strongest toward $where · confidence ${"%.2f".format(sweep.confidence)} · depth ${"%.1f".format(sweep.amplitudeDb)} dB · ${sweep.bins.size} sectors"
        else "No clear direction (confidence ${"%.2f".format(sweep.confidence)}, ${sweep.bins.size} sectors) — move closer or pick a stronger BLE target",
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
