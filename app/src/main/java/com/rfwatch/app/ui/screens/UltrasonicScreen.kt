package com.rfwatch.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.OkGreen
import com.rfwatch.app.ui.theme.RadarCyan
import com.rfwatch.app.ui.theme.WarnAmber
import com.rfwatch.core.cs.ToneKind
import com.rfwatch.core.cs.UltrasonicDetector
import com.rfwatch.core.cs.UltrasonicResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val LISTEN_MS = 20_000L

/**
 * On-demand ultrasonic beacon check. Listens with the microphone for 20 s while this screen is
 * open (never in the background), analyses 17–22 kHz in memory with [UltrasonicDetector] and
 * throws the audio away — nothing is recorded to storage. Results go to the event log.
 */
@Composable
fun UltrasonicScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var job by remember { mutableStateOf<Job?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    var result by remember { mutableStateOf<UltrasonicResult?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    val running = job?.isActive == true

    fun start() {
        result = null; message = null; progress = 0f
        job = scope.launch {
            val r = runCatching { listen(context) { progress = it } }
            r.onSuccess { res ->
                if (res == null) message = "Check interrupted — keep this screen open for the full 20 s."
                else { result = res; vm.logUltrasonicCheck(res) }
            }.onFailure {
                message = if (it is kotlinx.coroutines.CancellationException) "Check stopped — no result (keep this screen open for the full 20 s)."
                else "Couldn't use the microphone: ${it.message ?: it.javaClass.simpleName}"
            }
            job = null
        }
    }

    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) start() else message = "Microphone permission is needed for this check (choose \"Only this time\")."
    }

    // Android silences the mic for apps in the background — stop rather than report a false all-clear.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) job?.cancel() }
        lifecycle.addObserver(obs)
        onDispose { lifecycle.removeObserver(obs); job?.cancel() }
    }

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("ULTRASONIC BEACON CHECK", fontSize = 10.sp, color = RadarCyan, letterSpacing = 1.2.sp)
            Text("Hidden 17–22 kHz tones", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Some ads, TV broadcasts, shops and apps play near-silent high-pitched tones that apps on nearby phones listen " +
                    "for — to link your devices, confirm you watched an ad or detect you walking into a store. This listens for 20 s " +
                    "and looks for them. Audio is analysed in memory and thrown away; nothing is recorded.",
                fontSize = 12.sp, color = ConsoleMuted,
            )
        }
        item {
            if (running) {
                OutlinedButton(onClick = { job?.cancel() }, modifier = Modifier.fillMaxWidth()) { Text("Stop") }
                LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
                Text("Listening… ${((1 - progress) * LISTEN_MS / 1000).toInt()} s left — keep the phone still and this screen open.", fontSize = 12.sp, color = ConsoleMuted)
            } else {
                Button(onClick = {
                    if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) start()
                    else permission.launch(Manifest.permission.RECORD_AUDIO)
                }, modifier = Modifier.fillMaxWidth()) { Text(if (result == null) "Listen for 20 s" else "Listen again") }
                Text("Tip: when Android asks, choose \"Only this time\" — the app never needs the mic otherwise.", fontSize = 10.sp, color = ConsoleMuted)
            }
            message?.let { Text(it, fontSize = 12.sp, color = WarnAmber, modifier = Modifier.padding(top = 6.dp)) }
        }
        result?.let { r -> item { ResultCard(r) } }
        item {
            Text(
                "How to read it: BEACON-LIKE = bursts or tones that take turns, the way tracking beacons send data. STEADY = one " +
                    "constant pitch, usually electronics (phone chargers, LED lights, TVs, monitors). Turn suspect devices off one at a " +
                    "time and re-check to find the source.",
                fontSize = 10.sp, color = ConsoleMuted,
            )
        }
    }
}

@Composable
private fun ResultCard(r: UltrasonicResult) {
    val tone = when {
        r.beaconLike -> DangerRed
        r.tones.isNotEmpty() -> WarnAmber
        r.bandMaybeDeaf -> WarnAmber
        else -> OkGreen
    }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = ConsolePanel),
        border = BorderStroke(1.dp, tone.copy(alpha = 0.7f)),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                when {
                    r.beaconLike -> "Beacon-like signal found"
                    r.tones.any { it.kind == ToneKind.STEADY } -> "Steady tone only — probably electronics"
                    r.tones.isNotEmpty() -> "Brief blips only — inconclusive"
                    r.bandMaybeDeaf -> "Nothing heard — but this mic may not hear this band"
                    else -> "No ultrasonic tones"
                },
                fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = tone,
            )
            r.tones.forEach { t ->
                Text(
                    "${"%.2f".format(t.freqHz / 1000)} kHz · ${t.kind.name.replace('_', '-')} · +${t.snrDb.toInt()} dB above noise · " +
                        "present ${(t.presence * 100).toInt()}% of the time · ${t.bursts} burst(s)",
                    fontSize = 12.sp,
                )
            }
            if (r.alternatingGroup) Text("Several tones take turns — that's how data is sent.", fontSize = 12.sp, color = DangerRed)
            Text(
                "Band ${"%.1f".format(r.bandLowHz / 1000)}–${"%.1f".format(r.bandHighHz / 1000)} kHz · ${r.sampleRate} Hz · ${r.frames} frames · " +
                    "mic level in band ${(r.bandFloorDb - r.speechFloorDb).toInt()} dB vs speech band",
                fontSize = 10.sp, color = ConsoleMuted,
            )
            if (r.bandMaybeDeaf) Text(
                "The mic picks up very little in this band, so a clean result here doesn't prove there's nothing — many phone mics roll off above ~18 kHz.",
                fontSize = 10.sp, color = WarnAmber,
            )
        }
    }
}

/** Records [LISTEN_MS] of audio and analyses it; null when cancelled (e.g. app left the screen). */
@SuppressLint("MissingPermission") // caller checks RECORD_AUDIO
private suspend fun listen(context: Context, onProgress: (Float) -> Unit): UltrasonicResult? = withContext(Dispatchers.Default) {
    val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    val unprocessed = am.getProperty(AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED) == "true"
    val source = if (unprocessed) MediaRecorder.AudioSource.UNPROCESSED else MediaRecorder.AudioSource.VOICE_RECOGNITION
    var rec: AudioRecord? = null
    var rate = 0
    for (r in intArrayOf(48_000, 44_100)) {
        val min = AudioRecord.getMinBufferSize(r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        if (min <= 0) continue
        val a = AudioRecord(source, r, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, maxOf(min, r / 5))
        if (a.state == AudioRecord.STATE_INITIALIZED) { rec = a; rate = r; break } else a.release()
    }
    val recorder = rec ?: error("no supported sample rate")
    val detector = UltrasonicDetector(rate)
    val buf = ShortArray(4096)
    try {
        recorder.startRecording()
        val start = System.currentTimeMillis()
        while (isActive && System.currentTimeMillis() - start < LISTEN_MS) {
            val n = recorder.read(buf, 0, buf.size)
            if (n > 0) detector.feedPcm16(buf, n)
            withContext(Dispatchers.Main) { onProgress(((System.currentTimeMillis() - start).toFloat() / LISTEN_MS).coerceAtMost(1f)) }
        }
        if (!isActive) return@withContext null
        detector.result().also {
            android.util.Log.i("RFWatchUltra", "source=${if (unprocessed) "UNPROCESSED" else "VOICE_RECOGNITION"} " + it.summary() +
                " | floors band ${it.bandFloorDb.toInt()} dB speech ${it.speechFloorDb.toInt()} dB")
        }
    } finally {
        runCatching { recorder.stop() }
        recorder.release()
    }
}
