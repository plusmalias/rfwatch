package com.rfwatch.core.alerts

/** Alert categories, each with its own notification channel, vibration and TTS phrase. */
enum class AlertType {
    PRIORITY_NEARBY,
    PRIORITY_APPROACHING,
    TRACKER_FOLLOWING,
    NEW_AT_HOME,
    SWEEP_HIT,
    IMSI_CATCHER,
}

/**
 * Builds the spoken phrase for an alert. A direction is only ever spoken when we are
 * reasonably sure of it ([MIN_SPOKEN_CONFIDENCE]) and a relative bearing is available —
 * announcing a confident-sounding "rear left" from a guess would be worse than silence.
 *
 * Relative bearing is degrees clockwise from the direction the user currently faces
 * (0 = dead ahead), i.e. `targetBearing − userHeading` normalised to 0..360.
 */
object AlertPhrasing {

    const val MIN_SPOKEN_CONFIDENCE = 0.6

    private val SECTORS = listOf(
        "ahead", "ahead right", "right", "rear right",
        "behind", "rear left", "left", "ahead left",
    )

    fun relativeSector(relativeBearingDeg: Double): String {
        val norm = ((relativeBearingDeg % 360.0) + 360.0) % 360.0
        val idx = (((norm + 22.5) / 45.0).toInt()) % 8
        return SECTORS[idx]
    }

    fun phrase(type: AlertType, relativeBearingDeg: Double?, confidence: Double): String {
        val base = when (type) {
            AlertType.PRIORITY_NEARBY -> "Priority device nearby"
            AlertType.PRIORITY_APPROACHING -> "Priority device approaching"
            AlertType.TRACKER_FOLLOWING -> "Possible tracker following you"
            AlertType.NEW_AT_HOME -> "New device detected at home"
            AlertType.SWEEP_HIT -> "Surveillance device found"
            AlertType.IMSI_CATCHER -> "Possible IMSI catcher nearby"
        }
        val speakDirection = relativeBearingDeg != null && confidence > MIN_SPOKEN_CONFIDENCE
        return if (speakDirection) "$base, ${relativeSector(relativeBearingDeg!!)}" else base
    }
}
