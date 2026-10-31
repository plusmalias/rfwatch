package com.rfwatch.app.ui.screens

import androidx.compose.ui.graphics.Color
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.RadarCyan
import com.rfwatch.app.ui.theme.RadarPink
import com.rfwatch.app.ui.theme.WarnAmber
import kotlin.math.roundToInt

/** Shared category palette for radar contacts, inventory rows, and map markers. */
internal fun deviceCategoryColor(category: String?, classification: String = "UNKNOWN"): Color {
    val label = category.orEmpty().lowercase()
    return when {
        "tracker" in label -> RadarPink
        "axon" in label || "law-enforcement" in label -> DangerRed
        "emergency services" in label || "ambulance" in label -> Color(0xFF3B82F6)
        "camera" in label || "iot" in label -> WarnAmber
        "fleet" in label || "vehicle" in label -> Color(0xFFB79CFF)
        "wi-fi" in label || "wifi" in label -> Color(0xFF59B7FF)
        "bluetooth" in label || "ble" in label -> RadarCyan
        classification == "PRIORITY" -> DangerRed
        classification == "NOTABLE" -> WarnAmber
        else -> RadarCyan
    }
}

/** Uncalibrated path-loss proxy for relative proximity only; never a measured range. */
internal fun rssiDistanceEstimateMeters(rssi: Int): Double =
    Math.pow(10.0, (-59.0 - rssi.coerceIn(-110, -20)) / (10.0 * 2.4)).coerceIn(0.5, 100.0)

internal fun formatRssiDistance(meters: Double): String =
    if (meters < 10.0) String.format(java.util.Locale.US, "%.1f", meters)
    else "${(meters / 5.0).roundToInt() * 5}"
