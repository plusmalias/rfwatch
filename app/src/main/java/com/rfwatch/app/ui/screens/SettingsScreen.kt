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
                Text("Biometric app lock", fontSize = 14.sp)
                Text("Require fingerprint/face to open RFWatch.", fontSize = 12.sp, color = Color(0xFF9CA3AF))
            }
            Switch(checked = appLock, onCheckedChange = {
                appLock = it; prefs.edit().putBoolean("app_lock", it).apply()
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
