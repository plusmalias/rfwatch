package com.rfwatch.app.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.alerts.AlertManager
import kotlinx.coroutines.flow.SharedFlow

/**
 * Full-screen colour pulse shown over every tab while a loud alert dings (red = police,
 * orange = travelling with you, magenta = drone, amber = camera / new at home). Touches pass
 * through: the overlay never consumes input and fades out after a few pulses.
 */
@Composable
fun AlertFlashOverlay(flashes: SharedFlow<AlertManager.FlashRequest>) {
    var current by remember { mutableStateOf<AlertManager.FlashRequest?>(null) }
    val alpha = remember { Animatable(0f) }
    LaunchedEffect(flashes) { flashes.collect { current = it } }
    LaunchedEffect(current) {
        if (current == null) return@LaunchedEffect
        repeat(PULSES) {
            alpha.animateTo(0.78f, tween(110))
            alpha.animateTo(0.12f, tween(260))
        }
        alpha.animateTo(0f, tween(300))
        current = null
    }
    val req = current ?: return
    Box(
        Modifier.fillMaxSize().alpha(alpha.value).background(Color(req.colorArgb)),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            req.title.uppercase(),
            color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center, modifier = Modifier.padding(24.dp),
        )
    }
}

private const val PULSES = 4
