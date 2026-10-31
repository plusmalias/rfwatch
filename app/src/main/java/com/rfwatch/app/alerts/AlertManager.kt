package com.rfwatch.app.alerts

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationCompat
import com.rfwatch.core.alerts.AlertPhrasing
import com.rfwatch.core.alerts.AlertType
import com.rfwatch.core.alerts.LawEncounter
import java.util.Locale

/**
 * Raises alerts (step 11): each [AlertType] has its own notification channel, a distinct
 * vibration pattern, and a spoken phrase from the :core [AlertPhrasing] (which omits the
 * direction unless confidence is high). TTS uses the Australian English voice.
 */
class AlertManager(private val context: Context) : TextToSpeech.OnInitListener {

    private val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    private val tts = TextToSpeech(context, this)
    @Volatile private var ttsReady = false

    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        }

    init {
        createChannels()
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            tts.language = Locale("en", "AU")
            ttsReady = true
        }
    }

    /** Raise an alert. [relativeBearingDeg] is relative to the user's facing (0 = ahead). */
    fun raise(
        type: AlertType,
        title: String,
        deviceLabel: String?,
        relativeBearingDeg: Double?,
        confidence: Double,
        speak: Boolean = true,
    ): String {
        val phrase = AlertPhrasing.phrase(type, relativeBearingDeg, confidence)
        val text = if (deviceLabel != null) "$phrase ($deviceLabel)" else phrase

        val channel = channelId(type)
        val n = NotificationCompat.Builder(context, channel)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setPriority(if (type == AlertType.TRACKER_FOLLOWING) NotificationCompat.PRIORITY_LOW else NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setAutoCancel(true)
            .build()
        nm.notify(type.ordinal + 1000, n)

        if (type != AlertType.TRACKER_FOLLOWING) vibrate(type)
        if (speak && ttsReady) {
            tts.speak(phrase, TextToSpeech.QUEUE_ADD, null, "rfwatch-${type.name}")
        }
        return phrase
    }

    private val prefs = context.getSharedPreferences("rfwatch_settings", Context.MODE_PRIVATE)

    /**
     * Live, updating notification for a possible law-enforcement encounter. [loud] alerts with
     * heads-up, vibration and (if enabled) speech; otherwise the same notification is refreshed
     * silently. Disabled entirely by the "Police equipment alerts" setting.
     */
    fun lawEncounter(encounter: LawEncounter, loud: Boolean) {
        if (!prefs.getBoolean(PREF_LAW_ALERTS, true)) return
        val strongest = encounter.strongest ?: return
        val count = encounter.signatures.size
        val title = "Possible police equipment nearby"
        val text = "${strongest.label} · ${strengthWords(strongest.lastRssi)} (${strongest.lastRssi} dBm)" +
            if (count > 1) " · $count signatures" else ""
        val now = System.currentTimeMillis()
        val details = encounter.signatures.joinToString("\n") {
            "• ${it.label}: ${it.lastRssi} dBm, ${((now - it.lastSeenMs) / 1000).coerceAtLeast(0)} s ago"
        } + "\nVendor radio signatures only — not proof police are present."
        val n = NotificationCompat.Builder(context, LAW_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_alert)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$text\n$details"))
            .setPriority(NotificationCompat.PRIORITY_MAX)
            .setCategory(NotificationCompat.CATEGORY_ALARM)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOngoing(true)
            .setOnlyAlertOnce(!loud)
            .setWhen(encounter.startedMs).setShowWhen(true).setUsesChronometer(true)
            .setContentIntent(openAppIntent())
            .build()
        nm.notify(LAW_NOTIFICATION_ID, n)
        if (loud) {
            vibrate(if (encounter.hasPriority) AlertType.AXON_SIGNATURE else AlertType.LAW_ENFORCEMENT_SIGNATURE)
            if (ttsReady && prefs.getBoolean(PREF_LAW_SPEAK, true)) {
                tts.speak(
                    "Possible police equipment. ${strongest.label.substringBefore(" (")}, ${strengthWords(strongest.lastRssi)}.",
                    TextToSpeech.QUEUE_FLUSH, null, "rfwatch-law",
                )
            }
        }
    }

    /** Turns the live notification into a dismissible "last detected" summary. */
    fun lawEncounterEnded(encounter: LawEncounter) {
        if (!prefs.getBoolean(PREF_LAW_ALERTS, true)) return
        val time = java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(encounter.lastSeenMs))
        val n = NotificationCompat.Builder(context, LAW_CHANNEL)
            .setSmallIcon(android.R.drawable.ic_dialog_info)
            .setContentTitle("Police equipment last detected $time")
            .setContentText(encounter.signatures.joinToString { it.label.substringBefore(" (") })
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                encounter.signatures.joinToString("\n") { "• ${it.label}: strongest ${it.maxRssi} dBm" },
            ))
            .setOngoing(false).setAutoCancel(true).setOnlyAlertOnce(true)
            .setContentIntent(openAppIntent())
            .build()
        nm.notify(LAW_NOTIFICATION_ID, n)
    }

    private fun strengthWords(rssi: Int) = when {
        rssi >= -60 -> "very close"
        rssi >= -75 -> "nearby"
        rssi >= -85 -> "in range"
        else -> "distant"
    }

    private fun openAppIntent(): android.app.PendingIntent = android.app.PendingIntent.getActivity(
        context, 0,
        android.content.Intent(context, com.rfwatch.app.MainActivity::class.java)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_SINGLE_TOP),
        android.app.PendingIntent.FLAG_IMMUTABLE or android.app.PendingIntent.FLAG_UPDATE_CURRENT,
    )

    private fun vibrate(type: AlertType) {
        val pattern = when (type) {
            AlertType.PRIORITY_NEARBY -> longArrayOf(0, 200, 150, 200)
            AlertType.PRIORITY_APPROACHING -> longArrayOf(0, 120, 80, 120, 80, 320)
            AlertType.TRACKER_FOLLOWING -> longArrayOf(0, 400, 200, 400, 200, 400)
            AlertType.NEW_AT_HOME -> longArrayOf(0, 250, 500, 250)
            AlertType.SWEEP_HIT -> longArrayOf(0, 80, 60, 80, 60, 80)
            AlertType.IMSI_CATCHER -> longArrayOf(0, 500, 150, 150, 150, 500)
            AlertType.AXON_SIGNATURE -> longArrayOf(0, 250, 150, 250)
            AlertType.LAW_ENFORCEMENT_SIGNATURE -> longArrayOf(0, 350, 180, 350)
            AlertType.CAMERA_IOT_SIGNATURE -> longArrayOf(0, 120, 100, 120)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(VibrationEffect.createWaveform(pattern, -1))
        } else {
            @Suppress("DEPRECATION") vibrator.vibrate(pattern, -1)
        }
    }

    private fun channelId(type: AlertType) = if (type == AlertType.TRACKER_FOLLOWING) "ch_tracker_candidate_v2" else "ch_" + type.name.lowercase()

    private fun createChannels() {
        val channels = listOf(
            AlertType.PRIORITY_NEARBY to "Priority device nearby",
            AlertType.PRIORITY_APPROACHING to "Priority device approaching",
            AlertType.TRACKER_FOLLOWING to "Tracker following",
            AlertType.NEW_AT_HOME to "New device at home",
            AlertType.SWEEP_HIT to "Room sweep hit",
            AlertType.IMSI_CATCHER to "IMSI catcher / fake tower",
            AlertType.AXON_SIGNATURE to "Possible Axon device signature",
            AlertType.LAW_ENFORCEMENT_SIGNATURE to "Possible law-enforcement radio signature",
            AlertType.CAMERA_IOT_SIGNATURE to "Possible camera / IoT radio signature",
        ).map { (type, name) ->
            NotificationChannel(channelId(type), name, NotificationManager.IMPORTANCE_HIGH).apply {
                enableVibration(true)
            }
        } + NotificationChannel(LAW_CHANNEL, "Police equipment (live)", NotificationManager.IMPORTANCE_HIGH).apply {
            description = "Live alert while possible law-enforcement radio signatures are nearby"
            enableVibration(true)
            lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
        } + NotificationChannel("ch_tracker_candidate_v2", "Persistent item-tracker-like signal", NotificationManager.IMPORTANCE_LOW) +
            NotificationChannel(MONITOR_CHANNEL, "Monitoring active", NotificationManager.IMPORTANCE_LOW)
        channels.forEach { nm.createNotificationChannel(it) }
    }

    fun shutdown() {
        tts.stop(); tts.shutdown()
    }

    companion object {
        const val MONITOR_CHANNEL = "ch_monitor"
        const val LAW_CHANNEL = "ch_law_live"
        const val PREF_LAW_ALERTS = "law_alerts"
        const val PREF_LAW_SPEAK = "law_speak"
        private const val LAW_NOTIFICATION_ID = 4242
    }
}
