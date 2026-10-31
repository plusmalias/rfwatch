package com.rfwatch.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val ConsoleBackground = Color(0xFF070B10)
val ConsolePanel = Color(0xFF0D141C)
val ConsolePanelRaised = Color(0xFF121D27)
val ConsoleOutline = Color(0xFF243441)
val ConsoleText = Color(0xFFE4EDF2)
val ConsoleMuted = Color(0xFF91A4B0)
val RadarCyan = Color(0xFF4DE2C5)
val RadarPink = Color(0xFFF277A6)
val WarnAmber = Color(0xFFFFC66D)
val DangerRed = Color(0xFFFF6577)
val OkGreen = Color(0xFF63E6A5)
val SignalBlue = Color(0xFF69B9FF)

private val ConsoleColors = darkColorScheme(
    primary = RadarCyan,
    onPrimary = Color(0xFF06211D),
    secondary = SignalBlue,
    tertiary = WarnAmber,
    background = ConsoleBackground,
    onBackground = ConsoleText,
    surface = ConsolePanel,
    onSurface = ConsoleText,
    surfaceVariant = ConsolePanelRaised,
    onSurfaceVariant = ConsoleMuted,
    outline = ConsoleOutline,
    error = DangerRed,
    onError = Color(0xFF2A080D),
)

private val ConsoleShapes = Shapes()

@Composable
fun RfWatchTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ConsoleColors,
        shapes = ConsoleShapes,
        typography = MaterialTheme.typography.copy(
            headlineSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold, fontSize = 21.sp, letterSpacing = 0.2.sp),
            titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 16.sp),
            labelSmall = TextStyle(fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium, fontSize = 10.sp, letterSpacing = 0.5.sp),
        ),
        content = content,
    )
}
