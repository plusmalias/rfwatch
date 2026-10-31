package com.rfwatch.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val RadarCyan = Color(0xFF38BDF8)
val RadarPink = Color(0xFFF472B6)
val WarnAmber = Color(0xFFFBBF24)
val DangerRed = Color(0xFFEF4444)
val OkGreen = Color(0xFF34D399)

private val DarkColors = darkColorScheme(
    primary = RadarCyan,
    secondary = RadarPink,
    tertiary = WarnAmber,
    background = Color(0xFF07131A),
    surface = Color(0xFF0B1F2A),
    error = DangerRed,
)

private val LightColors = lightColorScheme(
    primary = Color(0xFF0369A1),
    secondary = Color(0xFFBE185D),
)

@Composable
fun RfWatchTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) DarkColors else LightColors,
        content = content,
    )
}
