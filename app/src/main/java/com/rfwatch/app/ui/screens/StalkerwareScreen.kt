package com.rfwatch.app.ui.screens

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rfwatch.app.RfWatchApp
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.OkGreen
import com.rfwatch.app.ui.theme.WarnAmber
import com.rfwatch.core.cs.AppAssessment
import com.rfwatch.core.cs.AppRisk
import com.rfwatch.core.cs.StalkerwareDb
import com.rfwatch.core.cs.StalkerwareScorer
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

/**
 * Checks this phone's own installed apps for stalkerware and mercenary spyware: package names,
 * signing certificates and APK file hashes against the bundled indicator lists, plus the
 * behaviour stalkerware needs (Accessibility, device admin, notification access, hidden icon,
 * sideloading, SMS / call-log / location permissions). Everything stays on the phone.
 */
@Composable
fun StalkerwareScreen(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as RfWatchApp).container }
    val scope = rememberCoroutineScope()
    var running by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf<List<AppAssessment>?>(null) }
    var showAll by remember { mutableStateOf(false) }
    val now = System.currentTimeMillis()

    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            Text("PHONE CHECK", fontSize = 10.sp, color = DangerRed, letterSpacing = 1.2.sp)
            Text("Stalkerware & spyware", fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Text(
                "Checks every installed app against ${StalkerwareDb.familyCount} known stalkerware / spyware families — " +
                    "${StalkerwareDb.packageCount} package names, ${StalkerwareDb.certificateCount} signing certificates and " +
                    "${StalkerwareDb.apkHashCount} APK file hashes (AssoEchap stalkerware-indicators; MVT indicators from Amnesty " +
                    "International, Citizen Lab and others) — and for the permissions stalkerware needs. Nothing leaves this phone.",
                fontSize = 12.sp, color = ConsoleMuted,
            )
        }
        item {
            Card(colors = CardDefaults.cardColors(containerColor = WarnAmber.copy(alpha = 0.08f)), border = BorderStroke(1.dp, WarnAmber.copy(alpha = 0.5f))) {
                Text(
                    "Safety first: removing stalkerware can alert the person who installed it. If you might be in danger, " +
                        "plan with a support service before removing anything — in Australia 1800RESPECT (1800 737 732), emergency 000. " +
                        "Use \"Export evidence report\" first if you may need proof.",
                    fontSize = 12.sp, modifier = Modifier.padding(10.dp),
                )
            }
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Button(enabled = !running, onClick = {
                    running = true
                    scope.launch {
                        val assessed = withContext(Dispatchers.IO) {
                            container.appScanner.scan().map { StalkerwareScorer.assess(it, System.currentTimeMillis()) }
                        }.sortedWith(compareBy<AppAssessment> { it.risk.ordinal }.thenByDescending { it.score })
                        results = assessed
                        running = false
                        container.repository.logStalkerwareScan(assessed)
                    }
                }) { Text(if (running) "Checking apps…" else "Scan this phone") }
                FilterChip(selected = showAll, onClick = { showAll = !showAll }, label = { Text("Show all apps") })
            }
            if (running) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        results?.let { list ->
            val critical = list.count { it.risk == AppRisk.CRITICAL }
            val high = list.count { it.risk == AppRisk.HIGH }
            val medium = list.count { it.risk == AppRisk.MEDIUM }
            item {
                Text(
                    "${list.size} apps checked · $critical known stalkerware/spyware · $high high-risk · $medium worth reviewing",
                    fontSize = 14.sp, color = if (critical + high > 0) DangerRed else if (medium > 0) WarnAmber else OkGreen,
                )
                if (critical + high == 0) Text("No known stalkerware or high-risk apps found. MEDIUM apps are often legitimate (launchers, password managers, accessibility tools) — review the reasons.", fontSize = 12.sp, color = ConsoleMuted)
            }
            items(list.filter { showAll || it.risk != AppRisk.LOW }, key = { it.facts.packageName }) { a -> AppRiskCard(a, now) }
        }
    }
}

@Composable
private fun AppRiskCard(a: AppAssessment, now: Long) {
    val context = LocalContext.current
    val f = a.facts
    val color = when (a.risk) { AppRisk.CRITICAL, AppRisk.HIGH -> DangerRed; AppRisk.MEDIUM -> WarnAmber; AppRisk.LOW -> ConsoleMuted }
    val fmt = DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
    var expanded by remember(f.packageName) { mutableStateOf(a.risk == AppRisk.CRITICAL) }
    Card(colors = CardDefaults.cardColors(containerColor = ConsolePanel), border = BorderStroke(1.dp, color.copy(alpha = 0.6f))) {
        Column(Modifier.fillMaxWidth().padding(10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text("${a.risk} · score ${a.score}" + (a.family?.let { " · ${it.name}" } ?: ""), color = color, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            Text(f.label, fontSize = 15.sp)
            Text(f.packageName, fontSize = 11.sp, color = ConsoleMuted)
            (if (expanded) a.reasons else a.reasons.take(2)).forEach { Text(it, fontSize = 11.sp) }
            androidx.compose.material3.TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide details" else "Show all ${a.reasons.size} reasons & details", fontSize = 12.sp)
            }
            if (expanded) Text(
                "Installer: ${f.installer ?: "none / sideloaded"} · ${if (f.isSystem) "system app" else "user app"} · " +
                    "icon ${if (f.hasLauncherIcon) "visible" else "HIDDEN"}",
                fontSize = 11.sp, color = ConsoleMuted,
            )
            if (expanded && f.firstInstallMs > 0) Text("Installed ${fmt.format(Date(f.firstInstallMs))} · updated ${fmt.format(Date(f.lastUpdateMs))} (${(now - f.firstInstallMs) / 86_400_000} days ago)", fontSize = 11.sp, color = ConsoleMuted)
            if (expanded) f.certSha1.forEach { Text("Signing cert SHA-1 $it", fontSize = 10.sp, color = ConsoleMuted) }
            if (expanded) f.apkSha256?.let { Text("APK SHA-256 $it", fontSize = 10.sp, color = ConsoleMuted) }
            val sensitive = f.grantedPermissions.filter { p -> SENSITIVE.any { p.endsWith(it) } }.map { it.substringAfterLast('.') }
            if (expanded && sensitive.isNotEmpty()) Text("Granted: ${sensitive.sorted().joinToString()}", fontSize = 10.sp, color = ConsoleMuted)
            if (a.risk != AppRisk.LOW) {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 4.dp)) {
                    OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${f.packageName}")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("App settings", fontSize = 12.sp) }
                    if (f.accessibilityEnabled) OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Accessibility", fontSize = 12.sp) }
                    if (f.deviceAdminActive) OutlinedButton(onClick = {
                        context.startActivity(Intent(Settings.ACTION_SECURITY_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                    }) { Text("Device admin", fontSize = 12.sp) }
                }
            }
        }
    }
}

private val SENSITIVE = listOf(
    "READ_SMS", "RECEIVE_SMS", "SEND_SMS", "READ_CALL_LOG", "RECORD_AUDIO", "CAMERA", "ACCESS_BACKGROUND_LOCATION",
    "ACCESS_FINE_LOCATION", "READ_CONTACTS", "PACKAGE_USAGE_STATS", "SYSTEM_ALERT_WINDOW", "READ_PHONE_STATE", "PROCESS_OUTGOING_CALLS",
)
