package com.rfwatch.app.ui.screens

import com.rfwatch.app.ui.toRowInfo

import android.content.Context
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
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
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.ConsolePanelRaised
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.core.df.HuntTone
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
    var targetQuery by remember { mutableStateOf("") }
    // Honour a hunt request from another screen (e.g. car sweep "Hunt this one").
    val requested by vm.huntRequest.collectAsState()
    LaunchedEffect(requested) {
        requested?.let { target = it; vm.huntRequest.value = null }
    }
    var sweeping by remember { mutableStateOf(false) }
    var hunt by remember { mutableStateOf(true) }
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
        Text("DIRECTION FINDING", fontSize = 10.sp, color = RadarCyan, letterSpacing = 1.5.sp)
        Text("Find a device", fontSize = 21.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = hunt, enabled = !sweeping, onClick = { hunt = true }, label = { Text("Hunt (hot/cold)") })
            FilterChip(selected = !hunt && guided, enabled = !sweeping, onClick = { hunt = false; guided = true }, label = { Text("Sweep: steps") })
            if (hasCompass) {
                FilterChip(selected = !hunt && !guided, enabled = !sweeping, onClick = { hunt = false; guided = false }, label = { Text("Sweep: compass") })
            }
        }
        if (!hasCompass && !hunt) {
            Text(
                "This phone has no compass (magnetometer), so the sweep uses guided steps: " +
                    "the direction is relative to where you start facing.",
                fontSize = 12.sp, color = WarnAmber,
            )
        }
        Text(
            if (hunt) "Walk around with the phone: the reading rises and the beeps speed up as you get closer. " +
                "Most reliable way to find a nearby device — no compass needed."
            else if (guided) "Hold the phone flat against your chest and face a landmark. Start, then turn " +
                "45° to your right at every buzz (${GUIDED_STEPS} steps, ~${GUIDED_STEPS * GUIDED_STEP_MS / 1000} s). " +
                "Your body shadows the signal, making it direction-dependent."
            else "Hold the phone flat against your chest and turn slowly through 360° over " +
                "about 30 s. Your body shadows the signal, making it direction-dependent.",
            fontSize = 12.sp, color = Color(0xFF9CA3AF),
        )

        if (target == null) {
            Text("TAP A DEVICE TO FIND IT  ·  trackers first", fontSize = 10.sp, color = ConsoleMuted, letterSpacing = 0.5.sp, modifier = Modifier.padding(top = 8.dp, bottom = 4.dp))
            // Trackers and flagged devices first, then BLE (updates faster), then strongest.
            val stored = vm.devices.collectAsState().value.associateBy { it.id }
            val q = targetQuery.trim().lowercase()
            val candidates = contacts.values
                .map { it to it.toRowInfo(stored[it.id]) }
                .filter { (c, info) -> q.isEmpty() || "${info.title} ${info.subtitle} ${c.id}".lowercase().contains(q) }
                .sortedWith(compareBy<Pair<LiveContact, com.rfwatch.app.ui.DeviceRowInfo>> { (c, info) ->
                    when { info.badge == "CHECK" -> 0; c.kind == com.rfwatch.core.id.DeviceKind.TRACKER || c.trackerType != null -> 1; else -> 2 }
                }.thenBy { it.first.band != Band.BLE }.thenByDescending { it.first.lastRssi })
            androidx.compose.material3.OutlinedTextField(
                value = targetQuery, onValueChange = { targetQuery = it }, singleLine = true,
                placeholder = { Text("Search ${contacts.size} nearby devices") }, modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                items(candidates, key = { it.first.id }) { (c, info) ->
                    com.rfwatch.app.ui.DeviceRowCompact(info) { target = c.id; finishedGuided = false }
                }
            }
        } else {
            Text("ACTIVE TARGET", fontSize = 10.sp, color = ConsoleMuted, letterSpacing = 1.sp, modifier = Modifier.padding(top = 8.dp))
            Text(
                "Target: ${targetContact?.label ?: target} · ${targetContact?.band ?: ""} · now ${targetContact?.lastRssi ?: "?"} dBm",
                fontSize = 14.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Medium,
            )
            if (targetContact?.band == Band.WIFI) {
                Text("Wi-Fi targets update only every ~30 s — expect too few samples. Prefer a Bluetooth device.", fontSize = 11.sp, color = WarnAmber)
            }
            if (hunt) {
                HuntPanel(vm, target!!, onChange = { target = null })
                return@Column
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
    val strongest = sweep.bins.maxByOrNull { it.medianRssi }
    val spread = if (sweep.bins.isEmpty()) 0.0 else sweep.bins.maxOf { it.medianRssi } - sweep.bins.minOf { it.medianRssi }
    Text(
        if (where != null) "Strongest toward $where · confidence ${"%.2f".format(sweep.confidence)} · depth ${"%.1f".format(sweep.amplitudeDb)} dB"
        else "No clear direction (confidence ${"%.2f".format(sweep.confidence)})",
        fontSize = 13.sp,
    )
    strongest?.let { b ->
        val angle = b.centerDeg.toInt() - (b.centerDeg.toInt() % 5)
        Text(
            "Strongest step: ${if (relative) "$angle° right of start" else "$angle°"} (${b.medianRssi.toInt()} dBm) · " +
                "signal changed ${"%.1f".format(spread)} dB while turning",
            fontSize = 12.sp, color = RadarPink,
        )
    }
    if (spread < 4.0) {
        Text(
            "The signal hardly changed while turning — the target is probably far away or reflections dominate. Use Hunt and walk toward it instead.",
            fontSize = 11.sp, color = WarnAmber,
        )
    }
}

private fun DrawScope.drawPolar(sweep: SweepResult?) {
    val cx = size.width / 2f; val cy = size.height / 2f
    val maxR = min(cx, cy) * 0.9f
    val grid = Color(0xFF123240)
    for (i in 1..3) drawCircle(grid, maxR * i / 3f, Offset(cx, cy), style = Stroke(1.5f))
    // "Start / ahead" tick at the top.
    drawLine(RadarCyan.copy(alpha = 0.6f), Offset(cx, cy - maxR - 4f), Offset(cx, cy - maxR + 14f), strokeWidth = 4f)
    if (sweep == null || sweep.bins.isEmpty()) return

    val minV = sweep.bins.minOf { it.medianRssi }
    val maxV = sweep.bins.maxOf { it.medianRssi }
    val span = (maxV - minV).coerceAtLeast(1.0)
    val strongest = sweep.bins.maxByOrNull { it.medianRssi }
    // One wedge per sector, length = relative signal; the strongest is highlighted.
    val halfWidth = min(22.0, 170.0 / sweep.bins.size)
    sweep.bins.forEach { b ->
        val r = (maxR * (0.25 + 0.75 * ((b.medianRssi - minV) / span))).toFloat()
        val color = if (b === strongest) RadarPink else RadarCyan.copy(alpha = 0.45f)
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(cx, cy)
            var d = b.centerDeg - halfWidth
            while (d <= b.centerDeg + halfWidth + 0.01) {
                val a = Math.toRadians(d)
                lineTo(cx + (r * sin(a)).toFloat(), cy - (r * cos(a)).toFloat())
                d += 2.0
            }
            close()
        }
        drawPath(path, color)
    }
    // Fitted direction arrow, only when the fit is confident.
    sweep.bearingDeg?.let { phi ->
        val ar = Math.toRadians(phi)
        drawLine(Color.White, Offset(cx, cy),
            Offset(cx + (maxR * sin(ar)).toFloat(), cy - (maxR * cos(ar)).toFloat()), strokeWidth = 5f)
    }
}

/**
 * Hot/cold hunting: streams every advert from the target, smooths it (EWMA) and compares it
 * with the level a few seconds ago, with optional Geiger-style beeps from [HuntTone].
 */
@Composable
private fun HuntPanel(vm: RfWatchViewModel, targetId: String, onChange: () -> Unit) {
    val sample by vm.hunt.collectAsState()
    var ewma by remember(targetId) { mutableStateOf<Double?>(null) }
    var reference by remember(targetId) { mutableStateOf<Double?>(null) }
    var lastHeardMs by remember(targetId) { mutableStateOf(0L) }
    var nowMs by remember { mutableStateOf(System.currentTimeMillis()) }
    var sound by remember { mutableStateOf(true) }
    val history = remember(targetId) { ArrayDeque<Pair<Long, Double>>() }

    DisposableEffect(targetId) {
        vm.setHuntTarget(targetId)
        onDispose { vm.setHuntTarget(null) }
    }
    LaunchedEffect(sample) {
        val s = sample ?: return@LaunchedEffect
        val next = ewma?.let { it + HUNT_ALPHA * (s.rssi - it) } ?: s.rssi.toDouble()
        ewma = next
        lastHeardMs = s.timestampMs
        history.addLast(s.timestampMs to next)
        while (history.isNotEmpty() && s.timestampMs - history.first().first > HUNT_TREND_WINDOW_MS) history.removeFirst()
        reference = history.firstOrNull()?.second
    }
    LaunchedEffect(Unit) { while (true) { nowMs = System.currentTimeMillis(); delay(500) } }
    // Geiger beeps: faster and higher as the smoothed signal rises.
    LaunchedEffect(sound) {
        if (!sound) return@LaunchedEffect
        val tone = runCatching { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }.getOrNull() ?: return@LaunchedEffect
        try {
            while (true) {
                val level = ewma
                val fresh = System.currentTimeMillis() - lastHeardMs < HUNT_LOST_MS
                if (level != null && fresh) {
                    tone.startTone(ToneGenerator.TONE_PROP_BEEP, 35)
                    delay((1000.0 / HuntTone.pulseRateHz(level)).toLong().coerceAtLeast(60L))
                } else delay(400)
            }
        } finally { tone.release() }
    }

    val level = ewma
    val lost = level == null || nowMs - lastHeardMs > HUNT_LOST_MS
    val trend = if (level != null && reference != null) HuntTone.proximity(level - reference!!) else HuntTone.Proximity.SAME
    Column(Modifier.fillMaxWidth().padding(top = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            if (level == null) "Waiting for the target to advertise…" else "${level.toInt()} dBm",
            fontSize = 44.sp, color = if (lost) Color(0xFF6B7280) else RadarCyan,
        )
        val fraction = level?.let { ((it + 100.0) / 70.0).coerceIn(0.0, 1.0).toFloat() } ?: 0f
        Canvas(Modifier.fillMaxWidth().height(22.dp)) {
            drawRect(Color(0xFF123240))
            drawRect(if (trend == HuntTone.Proximity.HOTTER) RadarPink else RadarCyan, size = size.copy(width = size.width * fraction))
        }
        Text(
            when {
                lost -> "Signal lost — last heard ${((nowMs - lastHeardMs) / 1000).coerceAtLeast(0)} s ago. Go back toward where it was stronger."
                trend == HuntTone.Proximity.HOTTER -> "HOTTER ↑  getting closer"
                trend == HuntTone.Proximity.COLDER -> "COLDER ↓  moving away"
                else -> "Steady — try another direction"
            },
            fontSize = 18.sp,
            color = when { lost -> WarnAmber; trend == HuntTone.Proximity.HOTTER -> RadarPink; else -> Color(0xFF9CA3AF) },
        )
        Text(
            "Rough distance from signal alone: ~${level?.let { formatRssiDistance(rssiDistanceEstimateMeters(it.toInt())) } ?: "?"} m " +
                "(walls and the device's transmit power change this a lot). Pause a few seconds at each spot — readings are smoothed.",
            fontSize = 11.sp, color = Color(0xFF9CA3AF),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilterChip(selected = sound, onClick = { sound = !sound }, label = { Text(if (sound) "Beeps on" else "Beeps off") })
            Button(onClick = onChange) { Text("Change target") }
        }
    }
}

private const val HUNT_ALPHA = 0.25
private const val HUNT_TREND_WINDOW_MS = 4_000L
private const val HUNT_LOST_MS = 15_000L
