package com.rfwatch.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.RadarCyan
import com.rfwatch.app.ui.theme.RadarPink
import com.rfwatch.core.df.EstimateStatus
import com.rfwatch.core.geo.LocalFrame

/**
 * Session map (step 10). The offline MapLibre MBTiles base layer is the one remaining
 * integration hook (the dependency is wired; see README "Map"). This screen renders the
 * session OVERLAYS — located emitters and their 2σ error ellipses in a local ENU
 * projection — with a Compose canvas, so the overlay geometry is verifiable and fully
 * offline regardless of the base layer.
 */
@Composable
fun MapScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier) {
    val contacts by vm.contacts.collectAsState()
    val located = contacts.values.filter {
        it.estimate?.status == EstimateStatus.LOCATED && it.estimate?.lat != null
    }

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("Session map", fontSize = 18.sp)
        Text(
            "Offline overlays: located emitters with 2σ error ellipses. Base layer: import " +
                "an MBTiles file (MapLibre) — default build fetches no tiles from the network.",
            fontSize = 12.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(bottom = 8.dp),
        )
        if (located.isEmpty()) {
            Text("No located emitters yet. Use Locate on the radar after a drive-by.", fontSize = 13.sp)
            return@Column
        }

        // Anchor an ENU frame at the first located emitter and plot all of them.
        val anchor = located.first().estimate!!
        val frame = LocalFrame(anchor.lat!!, anchor.lon!!)
        Canvas(Modifier.fillMaxWidth().weight(1f)) {
            val pad = 40f
            val pts = located.map { c ->
                val e = c.estimate!!
                val p = frame.toEnu(e.lat!!, e.lon!!)
                Triple(c, p.east, p.north)
            }
            val minE = pts.minOf { it.second } - 100; val maxE = pts.maxOf { it.second } + 100
            val minN = pts.minOf { it.third } - 100; val maxN = pts.maxOf { it.third } + 100
            val sx = (size.width - 2 * pad) / (maxE - minE).coerceAtLeast(1.0)
            val sy = (size.height - 2 * pad) / (maxN - minN).coerceAtLeast(1.0)
            val s = minOf(sx, sy)
            fun screen(e: Double, n: Double) = Offset(
                (pad + (e - minE) * s).toFloat(),
                (size.height - pad - (n - minN) * s).toFloat(),
            )
            pts.forEach { (c, e, n) ->
                val est = c.estimate!!
                val center = screen(e, n)
                est.ellipse?.let { el ->
                    drawOval(
                        color = RadarPink.copy(alpha = 0.2f),
                        topLeft = Offset(center.x - (el.semiMajorM * s).toFloat(), center.y - (el.semiMinorM * s).toFloat()),
                        size = Size((2 * el.semiMajorM * s).toFloat(), (2 * el.semiMinorM * s).toFloat()),
                    )
                    drawOval(
                        color = RadarPink,
                        topLeft = Offset(center.x - (el.semiMajorM * s).toFloat(), center.y - (el.semiMinorM * s).toFloat()),
                        size = Size((2 * el.semiMajorM * s).toFloat(), (2 * el.semiMinorM * s).toFloat()),
                        style = Stroke(1.5f),
                    )
                }
                drawCircle(RadarCyan, 7f, center)
            }
        }
    }
}
