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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.repo.LiveContact
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.RadarCyan
import com.rfwatch.app.ui.theme.RadarPink
import com.rfwatch.app.ui.theme.WarnAmber
import com.rfwatch.core.df.EstimateStatus
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * Radar v2 (step 5). Heading-up: the display rotates with the device, so the top of the
 * scope is whatever direction the phone is pointing. Contacts with a confident estimate are
 * drawn at their true relative bearing and scaled distance with an uncertainty wedge;
 * contacts without one sit on the grey outer ring labelled "bearing unknown"; mobile
 * emitters show an approach/recede arrow. Below the scope, each contact lists its actions.
 */
@Composable
fun RadarScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier) {
    val heading by vm.heading.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val list = contacts.values.sortedByDescending { it.lastRssi }
    val facing = heading.deg ?: 0.0

    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text(
            "Heading ${heading.deg?.let { "${it.toInt()}°" } ?: "—"} · ${heading.source}",
            fontSize = 13.sp,
        )
        Canvas(
            Modifier.fillMaxWidth().aspectRatio(1f).padding(8.dp)
        ) {
            drawRadar(list, facing)
        }
        Text("Contacts (${list.size})", fontSize = 14.sp, modifier = Modifier.padding(top = 8.dp))
        LazyColumn(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items(list, key = { it.id }) { c -> ContactRow(c, vm) }
        }
    }
}

private fun DrawScope.drawRadar(contacts: List<LiveContact>, facingDeg: Double) {
    val cx = size.width / 2f
    val cy = size.height / 2f
    val maxR = min(cx, cy) * 0.92f
    val grid = Color(0xFF123240)

    // Range rings.
    for (i in 1..3) {
        drawCircle(grid, radius = maxR * i / 3f, center = Offset(cx, cy), style = Stroke(1.5f))
    }
    // Cross-hair; "up" is where the device points (heading-up).
    drawLine(grid, Offset(cx, cy - maxR), Offset(cx, cy + maxR), strokeWidth = 1f)
    drawLine(grid, Offset(cx - maxR, cy), Offset(cx + maxR, cy), strokeWidth = 1f)
    // Own-position marker.
    drawCircle(RadarCyan, radius = 5f, center = Offset(cx, cy))

    val maxDist = (contacts.mapNotNull { it.estimate?.distanceFromUserM }.maxOrNull() ?: 100.0).coerceAtLeast(50.0)

    contacts.forEach { c ->
        val est = c.estimate
        val color = colorFor(c)
        if (est != null && est.status == EstimateStatus.LOCATED && est.bearingFromUserDeg != null) {
            val rel = Math.toRadians(est.bearingFromUserDeg!! - facingDeg)
            val rNorm = ((est.distanceFromUserM ?: maxDist) / maxDist).coerceIn(0.1, 1.0)
            val r = maxR * rNorm.toFloat()
            val px = cx + (r * sin(rel)).toFloat()
            val py = cy - (r * cos(rel)).toFloat()
            // Uncertainty wedge: angular half-width from the ellipse vs distance.
            val halfWedge = est.ellipse?.let {
                Math.toDegrees(Math.atan2(it.semiMajorM, (est.distanceFromUserM ?: maxDist).coerceAtLeast(1.0)))
            } ?: 15.0
            drawWedge(cx, cy, r, est.bearingFromUserDeg!! - facingDeg, halfWedge, color.copy(alpha = 0.18f))
            drawCircle(color, radius = 9f, center = Offset(px, py))
        } else {
            // Bearing unknown → grey outer ring, spread by hash so they don't overlap.
            val a = Math.toRadians((c.id.hashCode().and(0xFFFF)) % 360.0)
            val px = cx + (maxR * sin(a)).toFloat()
            val py = cy - (maxR * cos(a)).toFloat()
            drawCircle(Color(0xFF6B7280), radius = 6f, center = Offset(px, py))
        }
    }
}

private fun DrawScope.drawWedge(cx: Float, cy: Float, r: Float, centerDeg: Double, halfDeg: Double, color: Color) {
    val steps = 10
    val start = centerDeg - halfDeg
    val path = androidx.compose.ui.graphics.Path().apply {
        moveTo(cx, cy)
        for (i in 0..steps) {
            val a = Math.toRadians(start + (2 * halfDeg) * i / steps)
            lineTo(cx + (r * sin(a)).toFloat(), cy - (r * cos(a)).toFloat())
        }
        close()
    }
    drawPath(path, color)
}

private fun colorFor(c: LiveContact): Color = when (c.classification) {
    "TRACKER" -> RadarPink
    "PRIORITY" -> DangerRed
    "NOTABLE" -> WarnAmber
    else -> RadarCyan
}

@Composable
private fun ContactRow(c: LiveContact, vm: RfWatchViewModel) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(10.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(c.label, fontSize = 14.sp)
                val est = c.estimate
                val detail = when {
                    est?.status == EstimateStatus.LOCATED ->
                        "${est.bearingFromUserDeg?.toInt() ?: "?"}° · ${est.distanceFromUserM?.toInt() ?: "?"} m · conf ${"%.2f".format(est.confidence)}"
                    est?.status == EstimateStatus.MOBILE -> "MOBILE · ${est.trend}"
                    else -> "${c.classification} · ${c.lastRssi} dBm"
                }
                Text(detail, fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
            Button(onClick = { vm.estimate(c.id) {} }) { Text("Locate") }
        }
    }
}
