package com.rfwatch.app.ui.screens

import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.RfWatchApp
import com.rfwatch.app.alerts.AlertManager
import com.rfwatch.app.BuildConfig
import com.rfwatch.app.ui.theme.OkGreen
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.core.data.RetentionPolicy
import kotlinx.coroutines.launch

/**
 * Settings (step 11): retention window with auto-purge, biometric app-lock preference, and
 * a wipe-all-data action (guarded by a confirmation).
 */
@Composable
fun SettingsScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("rfwatch_settings", Context.MODE_PRIVATE) }

    var retentionDays by remember {
        mutableFloatStateOf(prefs.getInt("retention_days", RetentionPolicy.DEFAULT_RETENTION_DAYS).toFloat())
    }
    var appLock by remember { mutableStateOf(prefs.getBoolean("app_lock", false)) }
    var confirmWipe by remember { mutableStateOf(false) }
    var lawAlerts by remember { mutableStateOf(prefs.getBoolean(AlertManager.PREF_LAW_ALERTS, true)) }
    var lawSpeak by remember { mutableStateOf(prefs.getBoolean(AlertManager.PREF_LAW_SPEAK, true)) }
    var emergencyAlerts by remember { mutableStateOf(prefs.getBoolean(AlertManager.PREF_EMERGENCY_ALERTS, true)) }
    var flashScreen by remember { mutableStateOf(prefs.getBoolean(AlertManager.PREF_FLASH_SCREEN, true)) }
    var flashTorch by remember { mutableStateOf(prefs.getBoolean(AlertManager.PREF_FLASH_TORCH, false)) }
    val scope = rememberCoroutineScope()

    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
        Text("PREFERENCES", fontSize = 10.sp, color = OkGreen, letterSpacing = 1.3.sp)
        Text("Settings", fontSize = 21.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)

        Text("Data retention: ${retentionDays.toInt()} days", fontSize = 14.sp, modifier = Modifier.padding(top = 16.dp))
        Text("Observations and alerts older than this are purged automatically.", fontSize = 12.sp, color = Color(0xFF9CA3AF))
        Slider(
            value = retentionDays,
            onValueChange = { retentionDays = it },
            onValueChangeFinished = { prefs.edit().putInt("retention_days", retentionDays.toInt()).apply() },
            valueRange = 1f..90f,
        )

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Lock with phone PIN / pattern", fontSize = 14.sp)
                Text("Ask for your phone's PIN / pattern (or fingerprint, if the phone has one) to open RFWatch.", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
            Switch(checked = appLock, onCheckedChange = {
                if (it && !com.rfwatch.app.discreet.Discreet.canUseDeviceCredential(context)) {
                    android.widget.Toast.makeText(context, "Set a screen lock (PIN / pattern) on the phone first", android.widget.Toast.LENGTH_LONG).show()
                } else {
                    appLock = it; prefs.edit().putBoolean("app_lock", it).apply()
                }
            })
        }

        Row(Modifier.fillMaxWidth().padding(top = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Police equipment alerts", fontSize = 14.sp)
                Text("Live heads-up notification while possible law-enforcement radio signatures (Axon, agency vehicle routers, public-safety radios, ALPR) are nearby.", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
            Switch(checked = lawAlerts, onCheckedChange = {
                lawAlerts = it; prefs.edit().putBoolean(AlertManager.PREF_LAW_ALERTS, it).apply()
            })
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Speak police alerts", fontSize = 14.sp)
                Text("Say \"Possible police equipment…\" when an encounter starts.", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
            Switch(checked = lawSpeak, enabled = lawAlerts, onCheckedChange = {
                lawSpeak = it; prefs.edit().putBoolean(AlertManager.PREF_LAW_SPEAK, it).apply()
            })
        }

        val discreet = com.rfwatch.app.discreet.Discreet
        var disguise by remember { mutableStateOf(discreet.isDisguised(context)) }
        var neutral by remember { mutableStateOf(prefs.getBoolean(discreet.PREF_NEUTRAL_NOTIFICATIONS, false)) }
        var shake by remember { mutableStateOf(discreet.shakeToHide(context)) }
        var hasPin by remember { mutableStateOf(discreet.hasPin(context)) }
        var pinInput by remember { mutableStateOf("") }
        Text("Discreet mode", fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
        Text(
            "For when your phone may be checked by someone else. With a PIN set, opening the app shows a working calculator — " +
                "type the PIN and press = to get in. Screenshots and the Recents preview are blocked while any of these are on.",
            fontSize = 12.sp, color = Color(0xFF9CA3AF),
        )
        androidx.compose.material3.OutlinedTextField(
            value = pinInput, onValueChange = { v -> pinInput = v.filter { it.isDigit() }.take(12) },
            label = { Text(if (hasPin) "New PIN (4–12 digits)" else "Set a PIN (4–12 digits)") }, singleLine = true,
            visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
            keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
        )
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            Button(enabled = pinInput.length >= 4, onClick = { discreet.setPin(context, pinInput); pinInput = ""; hasPin = true }) { Text("Save PIN") }
            if (hasPin) TextButton(onClick = { discreet.setPin(context, null); hasPin = false; if (disguise) { discreet.setDisguised(context, false); disguise = false } }) { Text("Remove PIN") }
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Disguise as \"Calculator\"", fontSize = 14.sp)
                Text(if (hasPin) "App drawer shows a Calculator icon instead of RFWatch; locks every time you leave." else "Set a PIN first so you can get back in.", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
            Switch(checked = disguise, enabled = hasPin || disguise, onCheckedChange = { discreet.setDisguised(context, it); disguise = it })
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Neutral notifications", fontSize = 14.sp)
                Text("Alerts show only \"Reminder · Open the app for details\", no spoken alerts (always on while disguised).", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
            Switch(checked = neutral || disguise, enabled = !disguise, onCheckedChange = { neutral = it; prefs.edit().putBoolean(discreet.PREF_NEUTRAL_NOTIFICATIONS, it).apply() })
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Shake to hide", fontSize = 14.sp)
                Text("A hard double shake closes RFWatch instantly and removes it from Recents (locks if a lock is set).", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
            Switch(checked = shake, onCheckedChange = { shake = it; prefs.edit().putBoolean(discreet.PREF_SHAKE_HIDE, it).apply() })
        }

        val repo = remember { (context.applicationContext as RfWatchApp).container.repository }
        val home by repo.homeStatus.collectAsState()
        var homeMessage by remember { mutableStateOf<String?>(null) }
        Text("Home watch", fontSize = 14.sp, modifier = Modifier.padding(top = 20.dp))
        Text(
            if (!home.isSet) "Set Home while you're there. RFWatch learns the devices normally around it, then alerts when a new tracker, GPS tracker, camera, drone, police device or Wi-Fi network keeps showing up at home."
            else if (!home.mature) "Learning: ${home.visitsLearned} of ${com.rfwatch.core.cs.Baseline.MIN_SESSIONS} home visits (20+ min each) recorded. Alerts start after that." +
                (if (home.inside) " You're home now." else "")
            else "Watching: ${home.visitsLearned} home visits learned." + (if (home.inside) " You're home now." else ""),
            fontSize = 12.sp, color = Color(0xFF9CA3AF),
        )
        Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 6.dp)) {
            Button(onClick = { scope.launch { homeMessage = if (repo.setHomeHere()) "Home set here." else "Need a GPS fix (±100 m) — go outside or wait a moment." } }) {
                Text(if (home.isSet) "Move Home here" else "Set Home here")
            }
            if (home.isSet) TextButton(onClick = { scope.launch { repo.clearHome(); homeMessage = "Home cleared." } }) { Text("Clear Home") }
        }
        homeMessage?.let { Text(it, fontSize = 12.sp, color = OkGreen) }

        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Ambulance / fire alerts", fontSize = 14.sp)
                Text("Alert when ambulance or fire-crew equipment is heard (defibrillators, stretchers, breathing apparatus, fire apparatus).", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
            Switch(checked = emergencyAlerts, onCheckedChange = { emergencyAlerts = it; prefs.edit().putBoolean(AlertManager.PREF_EMERGENCY_ALERTS, it).apply() })
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Flash screen on alerts", fontSize = 14.sp)
                Text("Pulse the whole screen in the alert colour while it dings (when RFWatch is open).", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
            Switch(checked = flashScreen, onCheckedChange = { flashScreen = it; prefs.edit().putBoolean(AlertManager.PREF_FLASH_SCREEN, it).apply() })
        }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Flash torch on alerts", fontSize = 14.sp)
                Text("Strobe the camera flash LED for police, drone, follower and camera alerts — works with the screen off.", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
            Switch(checked = flashTorch, onCheckedChange = { flashTorch = it; prefs.edit().putBoolean(AlertManager.PREF_FLASH_TORCH, it).apply() })
        }
        androidx.compose.material3.OutlinedButton(
            onClick = {
                (context.applicationContext as RfWatchApp).container.alertManager.raise(
                    com.rfwatch.core.alerts.AlertType.LAW_ENFORCEMENT_SIGNATURE, "Test alert",
                    "This is a test — nothing was detected", null, 0.0,
                )
            },
            modifier = Modifier.padding(top = 8.dp),
        ) { Text("Test alert (sound, vibration, flash)") }

        Button(
            onClick = { confirmWipe = true },
            modifier = Modifier.padding(top = 24.dp),
        ) { Text("Wipe all data") }

        Text(
            "Encrypted at rest with SQLCipher; the key is held in the Android Keystore. " +
                if (BuildConfig.FLAVOR == "mapsOnline") "Online map tiles use the network; scan records stay on-device." else "This offline build has no network permission.",
            fontSize = 11.sp, color = ConsoleMuted, modifier = Modifier.padding(top = 24.dp),
        )
    }

    if (confirmWipe) {
        AlertDialog(
            onDismissRequest = { confirmWipe = false },
            title = { Text("Wipe all data?") },
            text = { Text("This permanently deletes every device, observation, alert and baseline. It cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    // Clear through the repository: deleting the file under an open Room
                    // connection left the live database (and the device cache) intact.
                    val repo = (context.applicationContext as RfWatchApp).container.repository
                    scope.launch { repo.wipeAllData() }
                    confirmWipe = false
                }) { Text("Wipe") }
            },
            dismissButton = { TextButton(onClick = { confirmWipe = false }) { Text("Cancel") } },
        )
    }
}
