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
import kotlinx.coroutines.launch

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
        if (type != AlertType.TRACKER_FOLLOWING) flash(type, title)

        if (type != AlertType.TRACKER_FOLLOWING) vibrate(type)
        if (speak && ttsReady) {
            tts.speak(phrase, TextToSpeech.QUEUE_ADD, null, "rfwatch-${type.name}")
        }
        return phrase
    }

    private val prefs = context.getSharedPreferences("rfwatch_settings", Context.MODE_PRIVATE)

    /** A request to flash the open app's screen in [colorArgb] while the alert dings. */
    data class FlashRequest(val colorArgb: Long, val title: String)

    private val _flashes = kotlinx.coroutines.flow.MutableSharedFlow<FlashRequest>(extraBufferCapacity = 8)
    /** Collected by MainActivity to pulse a full-screen colour overlay. */
    val flashes: kotlinx.coroutines.flow.SharedFlow<FlashRequest> = _flashes
    private val strobeScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Default)
    private val strobing = java.util.concurrent.atomic.AtomicBoolean(false)

    private fun flashColor(type: AlertType): Long = when (type) {
        AlertType.AXON_SIGNATURE, AlertType.LAW_ENFORCEMENT_SIGNATURE, AlertType.PRIORITY_NEARBY,
        AlertType.PRIORITY_APPROACHING, AlertType.IMSI_CATCHER -> 0xFFFF3B4E
        AlertType.LAW_VEHICLE_FOLLOWING, AlertType.POSSIBLE_FOLLOWER -> 0xFFFF7A1A
        AlertType.DRONE_NEARBY -> 0xFFE040FB
        AlertType.CAMERA_IOT_SIGNATURE, AlertType.NEW_AT_HOME, AlertType.SWEEP_HIT -> 0xFFFFB020
        AlertType.TRACKER_FOLLOWING -> 0xFFF277A6
        AlertType.EMERGENCY_VEHICLE -> 0xFF3B82F6
        AlertType.ATTACK_TOOL, AlertType.BLE_SPAM, AlertType.EVIL_TWIN -> 0xFF22D3EE
        AlertType.SKIMMER_SUSPECTED -> 0xFFFF3B4E
    }

    /** Screen flash (if the app is open) and optional camera-torch strobe for a loud alert. */
    private fun flash(type: AlertType, title: String) {
        if (prefs.getBoolean(PREF_FLASH_SCREEN, true)) _flashes.tryEmit(FlashRequest(flashColor(type), title))
        if (prefs.getBoolean(PREF_FLASH_TORCH, false)) strobeTorch()
    }

    /** Pulses the back camera's flash LED; needs no permission and works with the screen off. */
    private fun strobeTorch() {
        if (!strobing.compareAndSet(false, true)) return
        strobeScope.launch {
            try {
                val cm = context.getSystemService(Context.CAMERA_SERVICE) as android.hardware.camera2.CameraManager
                val id = cm.cameraIdList.firstOrNull {
                    val c = cm.getCameraCharacteristics(it)
                    c.get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true &&
                        c.get(android.hardware.camera2.CameraCharacteristics.LENS_FACING) == android.hardware.camera2.CameraCharacteristics.LENS_FACING_BACK
                } ?: return@launch
                repeat(6) {
                    cm.setTorchMode(id, true); kotlinx.coroutines.delay(110)
                    cm.setTorchMode(id, false); kotlinx.coroutines.delay(140)
                }
            } catch (_: Exception) {
                // Camera in use by another app, or no flash: skip silently.
            } finally {
                strobing.set(false)
            }
        }
    }

    /**
     * Live, updating notification for a possible law-enforcement encounter. [loud] alerts with
     * heads-up, vibration and (if enabled) speech; otherwise the same notification is refreshed
     * silently. Disabled entirely by the "Police equipment alerts" setting.
     */
    fun lawEncounter(encounter: LawEncounter, loud: Boolean, emergencyNearby: Boolean = false) {
        if (!prefs.getBoolean(PREF_LAW_ALERTS, true)) return
        val strongest = encounter.strongest ?: return
        val count = encounter.signatures.size
        // Police, ambulance and fire share routers, light bars and radios; say so when EMS/fire gear is also heard.
        val title = if (emergencyNearby && !encounter.hasPriority) "Possible police / ambulance / fire vehicle nearby" else "Possible police equipment nearby"
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
            flash(AlertType.LAW_ENFORCEMENT_SIGNATURE, "POLICE EQUIPMENT · ${strongest.label.substringBefore(" (")}")
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

    fun emergencyAlertsEnabled(): Boolean = prefs.getBoolean(PREF_EMERGENCY_ALERTS, true)

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
            AlertType.DRONE_NEARBY -> longArrayOf(0, 90, 70, 90, 70, 90, 70, 300)
            AlertType.POSSIBLE_FOLLOWER -> longArrayOf(0, 600, 250, 600)
            AlertType.LAW_VEHICLE_FOLLOWING -> longArrayOf(0, 600, 150, 300, 150, 600)
            AlertType.EMERGENCY_VEHICLE -> longArrayOf(0, 200, 120, 200, 120, 200)
            AlertType.ATTACK_TOOL -> longArrayOf(0, 80, 60, 80, 60, 80, 60, 400)
            AlertType.BLE_SPAM -> longArrayOf(0, 60, 40, 60, 40, 60, 40, 60, 40, 60)
            AlertType.EVIL_TWIN -> longArrayOf(0, 500, 100, 100, 100, 500)
            AlertType.SKIMMER_SUSPECTED -> longArrayOf(0, 700, 200, 700)
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
            AlertType.DRONE_NEARBY to "Drone nearby (Remote ID / DroneID)",
            AlertType.POSSIBLE_FOLLOWER to "Device travelling with you",
            AlertType.LAW_VEHICLE_FOLLOWING to "Police equipment travelling with you",
            AlertType.EMERGENCY_VEHICLE to "Ambulance / fire equipment nearby",
            AlertType.ATTACK_TOOL to "Hacking tool nearby",
            AlertType.BLE_SPAM to "Bluetooth spam attack",
            AlertType.EVIL_TWIN to "Fake / evil-twin Wi-Fi",
            AlertType.SKIMMER_SUSPECTED to "Possible card skimmer",
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
        const val PREF_FLASH_SCREEN = "flash_screen"
        const val PREF_FLASH_TORCH = "flash_torch"
        const val PREF_EMERGENCY_ALERTS = "emergency_alerts"
        private const val LAW_NOTIFICATION_ID = 4242
    }
}
