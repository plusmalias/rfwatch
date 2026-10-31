package com.rfwatch.app.ui.screens

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import android.view.TextureView
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.WarnAmber

/** A small saturated bright spot in the frame, in display-normalised coordinates (0..1). */
private data class Spot(val x: Float, val y: Float, val sizePx: Int, val frames: Int, val contrast: Int = 0)

private const val STEADY_FRAMES = 8

private enum class FinderMode(val label: String) { IR("IR finder (front camera)"), LENS("Lens finder (back camera + torch)") }

/**
 * Hidden-camera finder for cameras that don't transmit (SD-card recorders):
 *  - IR: many front cameras lack an IR-cut filter, so a night-vision camera's IR LEDs show as
 *    bright purple/white points in a dark room.
 *  - Lens: with the torch on, a lens reflects light straight back as a sharp glint.
 * Frames are analysed on-device for small saturated spots that persist across frames; nothing is
 * recorded or saved.
 */
@Composable
fun CameraFinderScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    var mode by remember { mutableStateOf(FinderMode.IR) }
    var spots by remember { mutableStateOf<List<Spot>>(emptyList()) }
    var brightness by remember { mutableStateOf(0) }

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text("CAMERA FINDER", fontSize = 10.sp, color = DangerRed, letterSpacing = 1.2.sp)
        Text("Find cameras that don't transmit", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        Text(
            when (mode) {
                FinderMode.IR -> "Turn the room lights OFF. Point the screen side of the phone around the room slowly — smoke alarms, " +
                    "clocks, chargers, vents, toys, picture frames. Night-vision cameras' infrared LEDs appear as steady bright purple/white " +
                    "dots (circled). TV remotes flash IR too — press one at the phone to confirm the front camera sees IR."
                FinderMode.LENS -> "Dim the room. The torch is on: sweep slowly from 1–3 m away and watch for a tiny sharp glint that stays " +
                    "put as you move — a lens reflecting light straight back (circled). Check anything with a small hole: clocks, " +
                    "chargers, USB bricks, smoke alarms, screws, plush toys, shower heads."
            },
            fontSize = 12.sp, color = ConsoleMuted,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FinderMode.entries.forEach { m -> FilterChip(selected = mode == m, onClick = { mode = m; spots = emptyList() }, label = { Text(m.label, fontSize = 12.sp) }) }
        }
        if (!granted) {
            Text("Camera access is needed for this tool only. Frames are analysed live and never saved.", fontSize = 12.sp)
            Button(onClick = { launcher.launch(Manifest.permission.CAMERA) }) { Text("Allow camera") }
        } else {
            Box(Modifier.fillMaxWidth().aspectRatio(3f / 4f)) {
                CameraPreview(mode) { found, mean -> spots = found; brightness = mean }
                Canvas(Modifier.fillMaxSize()) {
                    spots.forEach { s ->
                        val c = if (s.frames >= STEADY_FRAMES) DangerRed else WarnAmber
                        drawCircle(c, radius = 28f + s.sizePx, center = Offset(s.x * size.width, s.y * size.height), style = Stroke(4f))
                    }
                }
            }
            val steady = spots.count { it.frames >= STEADY_FRAMES }
            Text(
                when {
                    mode == FinderMode.IR && brightness > 90 -> "Room is too bright (${brightness}/255) — turn the lights off for the IR finder."
                    spots.size >= 4 && steady == 0 -> "Busy scene — screens, TVs and small lights create bright points. Dim or avoid them and sweep again."
                    steady > 0 -> "$steady steady bright spot(s) circled red — look closely at what's there. Amber = brief / flickering."
                    spots.isNotEmpty() -> "${spots.size} brief bright spot(s) — keep the phone still on them to see if they persist."
                    else -> "No candidate spots right now. Sweep slowly."
                },
                fontSize = 13.sp, color = if (steady > 0) DangerRed else ConsoleMuted,
            )
            Text("False alarms: power LEDs, reflections off glass/metal, street lights. A real camera lens glint stays a pin-point as you move; IR LEDs often show as a small cluster.", fontSize = 11.sp, color = ConsoleMuted)
        }
    }
}

@SuppressLint("MissingPermission")
@Composable
private fun CameraPreview(mode: FinderMode, onAnalysis: (List<Spot>, Int) -> Unit) {
    val context = LocalContext.current
    val textureView = remember { TextureView(context) }
    DisposableEffect(mode) {
        val thread = HandlerThread("rfwatch-finder").apply { start() }
        val handler = Handler(thread.looper)
        val main = Handler(context.mainLooper)
        val cm = context.getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val facing = if (mode == FinderMode.IR) CameraCharacteristics.LENS_FACING_FRONT else CameraCharacteristics.LENS_FACING_BACK
        val cameraId = cm.cameraIdList.firstOrNull { cm.getCameraCharacteristics(it).get(CameraCharacteristics.LENS_FACING) == facing }
            ?: cm.cameraIdList.firstOrNull()
        val orientation = cameraId?.let { cm.getCameraCharacteristics(it).get(CameraCharacteristics.SENSOR_ORIENTATION) } ?: 90
        val reader = ImageReader.newInstance(320, 240, ImageFormat.YUV_420_888, 2)
        val tracker = SpotTracker(orientation, mirrored = facing == CameraCharacteristics.LENS_FACING_FRONT)
        reader.setOnImageAvailableListener({ r ->
            r.acquireLatestImage()?.use { img ->
                val plane = img.planes[0]
                val (found, mean) = tracker.analyse(plane.buffer, img.width, img.height, plane.rowStride)
                main.post { onAnalysis(found, mean) }
            }
        }, handler)
        var device: CameraDevice? = null
        var session: CameraCaptureSession? = null

        fun start(texture: SurfaceTexture) {
            val id = cameraId ?: return
            texture.setDefaultBufferSize(640, 480)
            val preview = Surface(texture)
            runCatching {
                cm.openCamera(id, object : CameraDevice.StateCallback() {
                    override fun onOpened(d: CameraDevice) {
                        device = d
                        @Suppress("DEPRECATION")
                        d.createCaptureSession(listOf(preview, reader.surface), object : CameraCaptureSession.StateCallback() {
                            override fun onConfigured(s: CameraCaptureSession) {
                                session = s
                                val req = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
                                    addTarget(preview); addTarget(reader.surface)
                                    if (mode == FinderMode.LENS) set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                                }
                                runCatching { s.setRepeatingRequest(req.build(), null, handler) }
                            }
                            override fun onConfigureFailed(s: CameraCaptureSession) = Unit
                        }, handler)
                    }
                    override fun onDisconnected(d: CameraDevice) { d.close() }
                    override fun onError(d: CameraDevice, error: Int) { d.close() }
                }, handler)
            }
        }

        if (textureView.isAvailable) textureView.surfaceTexture?.let(::start)
        textureView.surfaceTextureListener = object : TextureView.SurfaceTextureListener {
            override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) = start(st)
            override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
            override fun onSurfaceTextureDestroyed(st: SurfaceTexture) = true
            override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
        }
        onDispose {
            runCatching { session?.close() }
            runCatching { device?.close() }
            runCatching { reader.close() }
            thread.quitSafely()
        }
    }
    AndroidView(factory = { textureView }, modifier = Modifier.fillMaxSize())
}

/**
 * Finds small saturated bright blobs in the luma plane (downsampled), and tracks how many
 * consecutive frames each persists — a steady pin-point is what an IR LED or lens glint looks like.
 */
private class SpotTracker(private val sensorOrientation: Int, private val mirrored: Boolean) {
    private var previous: List<Spot> = emptyList()
    /** True when the frame has many bright pin-points (screens, fairy lights) — results are less reliable. */
    @Volatile var busy = false

    private companion object {
        const val MAX_SPOT_CELLS = 3
        const val RING = 4
        const val MIN_CONTRAST = 90
        const val BUSY_SCENE_SPOTS = 6
    }

    fun analyse(buf: java.nio.ByteBuffer, w: Int, h: Int, rowStride: Int): Pair<List<Spot>, Int> {
        val step = 4
        val gw = w / step; val gh = h / step
        val lum = IntArray(gw * gh)
        var sum = 0L
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val v = buf.get(gy * step * rowStride + gx * step).toInt() and 0xFF
            lum[gy * gw + gx] = v; sum += v
        }
        val mean = (sum / lum.size).toInt()
        val threshold = maxOf(235, mean + 90)
        val seen = BooleanArray(lum.size)
        val blobs = ArrayList<Spot>()
        for (i in lum.indices) {
            if (seen[i] || lum[i] < threshold) continue
            // Flood-fill the bright blob.
            val stack = ArrayDeque<Int>().apply { add(i) }
            var count = 0; var sx = 0; var sy = 0; var peak = 0
            seen[i] = true
            while (stack.isNotEmpty()) {
                val p = stack.removeLast()
                count++; sx += p % gw; sy += p / gw; peak = maxOf(peak, lum[p])
                val px = p % gw; val py = p / gw
                for ((dx, dy) in arrayOf(1 to 0, -1 to 0, 0 to 1, 0 to -1)) {
                    val nx = px + dx; val ny = py + dy
                    if (nx !in 0 until gw || ny !in 0 until gh) continue
                    val q = ny * gw + nx
                    if (!seen[q] && lum[q] >= threshold) { seen[q] = true; stack.add(q) }
                }
            }
            // Pin-points only (1–3 cells): text on a lit screen, lamps and windows are bigger.
            if (count > MAX_SPOT_CELLS) continue
            val cx = sx / count; val cy = sy / count
            // Local contrast: a lens glint / IR LED is far brighter than a ring around it; bright
            // text on a screen is not, because its surroundings are bright too.
            var ringSum = 0; var ringN = 0
            for (dy in -RING..RING) for (dx in -RING..RING) {
                if (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != RING) continue
                val x = cx + dx; val y = cy + dy
                if (x !in 0 until gw || y !in 0 until gh) continue
                ringSum += lum[y * gw + x]; ringN++
            }
            val contrast = peak - (if (ringN > 0) ringSum / ringN else mean)
            if (contrast < MIN_CONTRAST) continue
            val (dx, dy) = toDisplay((cx + 0.5f) / gw, (cy + 0.5f) / gh)
            blobs += Spot(dx, dy, count, 1, contrast)
        }
        busy = blobs.size > BUSY_SCENE_SPOTS
        blobs.sortByDescending { it.contrast }
        // Persistence: a blob near one from the previous frame inherits its frame count + 1.
        val tracked = blobs.take(if (busy) 4 else 8).map { b ->
            val prev = previous.minByOrNull { (it.x - b.x) * (it.x - b.x) + (it.y - b.y) * (it.y - b.y) }
            if (prev != null && kotlin.math.hypot((prev.x - b.x).toDouble(), (prev.y - b.y).toDouble()) < 0.06) b.copy(frames = prev.frames + 1) else b
        }
        previous = tracked
        return tracked to mean
    }

    /** Rotate sensor-normalised coordinates into the portrait preview. */
    private fun toDisplay(u: Float, v: Float): Pair<Float, Float> {
        val (x, y) = when (sensorOrientation) {
            90 -> (1f - v) to u
            270 -> v to (1f - u)
            180 -> (1f - u) to (1f - v)
            else -> u to v
        }
        return (if (mirrored) 1f - x else x) to y
    }
}
