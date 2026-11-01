package com.rfwatch.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.MoreHoriz
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rfwatch.app.monitor.MonitorService
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.screens.CellScreen
import com.rfwatch.app.ui.screens.DevicesScreen
import com.rfwatch.app.ui.screens.EventLogScreen
import com.rfwatch.app.ui.screens.MapScreen
import com.rfwatch.app.ui.screens.MoreScreen
import com.rfwatch.app.ui.screens.RadarScreen
import com.rfwatch.app.ui.screens.RoomSweepScreen
import com.rfwatch.app.ui.screens.SettingsScreen
import com.rfwatch.app.ui.screens.SweepScreen
import com.rfwatch.app.ui.theme.RfWatchTheme
import com.rfwatch.app.ui.theme.ConsoleBackground
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.ConsoleText
import com.rfwatch.app.ui.theme.OkGreen

private enum class Tab(val label: String) {
    RADAR("Radar"), DEVICES("Devices"), SWEEP("Find"), MAP("Map"), MORE("Protect"),
    CELL("Cell"), ROOM("Room"), LOG("Log"), SETTINGS("Settings"), SKIMMER("Skimmer"), STALKERWARE("Stalkerware"), CAR("Car check"), CAMERA_FINDER("Camera finder"), ULTRASONIC("Ultrasonic")
}

class MainActivity : androidx.fragment.app.FragmentActivity() {

    /** Discreet-mode lock: true while the calculator lock screen is shown. */
    private val locked = androidx.compose.runtime.mutableStateOf(false)
    private var backgroundAtMs = 0L
    private var shakeListener: android.hardware.SensorEventListener? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        locked.value = com.rfwatch.app.discreet.Discreet.lockEnabled(this)
        applySecureFlag()

        val repo = (application as RfWatchApp).container.repository

        setContent {
            RfWatchTheme {
                if (locked.value) {
                    val discreet = com.rfwatch.app.discreet.Discreet
                    val credential = discreet.biometricLock(this@MainActivity)
                    androidx.compose.runtime.LaunchedEffect(Unit) {
                        if (!discreet.hasPin(this@MainActivity) && credential) promptDeviceCredential()
                    }
                    com.rfwatch.app.ui.CalculatorLockScreen(
                        checkPin = { discreet.verifyPin(this@MainActivity, it) },
                        onUnlocked = { locked.value = false },
                        onBiometric = if (credential) ({ promptDeviceCredential() }) else null,
                    )
                } else {
                val vm: RfWatchViewModel = viewModel(factory = RfWatchViewModel.Factory(repo))

                val permLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestMultiplePermissions()
                ) { grants ->
                    if (grants.values.any { it }) MonitorService.start(this)
                }

                LaunchedEffect(Unit) {
                    permLauncher.launch(requiredPermissions())
                }

                var tab by remember { mutableStateOf(Tab.RADAR) }
                // Cross-screen device links (Details / Locate / Hunt / Map) switch tabs here;
                // the target screen selects the device and consumes the request.
                val deviceNav by vm.deviceNav.collectAsState()
                val devicesRequest by vm.devicesRequest.collectAsState()
                LaunchedEffect(devicesRequest) { if (devicesRequest != 0L) tab = Tab.DEVICES }
                LaunchedEffect(deviceNav) {
                    val nav = deviceNav ?: return@LaunchedEffect
                    tab = when (nav.dest) {
                        com.rfwatch.app.ui.DeviceDest.DETAILS -> Tab.DEVICES
                        com.rfwatch.app.ui.DeviceDest.LOCATE -> Tab.RADAR
                        com.rfwatch.app.ui.DeviceDest.HUNT -> Tab.SWEEP
                        com.rfwatch.app.ui.DeviceDest.MAP -> Tab.MAP
                    }
                    // Hunt is carried by huntRequest (SweepScreen already consumes it).
                    if (nav.dest == com.rfwatch.app.ui.DeviceDest.HUNT) vm.consumeNav(nav)
                }
                var locationEnabled by remember { mutableStateOf(isLocationEnabled()) }
                val lifecycleOwner = LocalLifecycleOwner.current
                DisposableEffect(lifecycleOwner) {
                    val observer = LifecycleEventObserver { _, event ->
                        if (event == Lifecycle.Event.ON_RESUME) {
                            locationEnabled = isLocationEnabled()
                        }
                    }
                    lifecycleOwner.lifecycle.addObserver(observer)
                    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
                }

                Box(Modifier.fillMaxSize()) {
                Scaffold(
                    containerColor = ConsoleBackground,
                    bottomBar = {
                        NavigationBar(
                            containerColor = ConsolePanel,
                            contentColor = ConsoleText,
                            tonalElevation = 0.dp,
                        ) {
                            val items = listOf(
                                Tab.RADAR to Icons.Filled.LocationOn,
                                Tab.DEVICES to Icons.Filled.List,
                                Tab.SWEEP to Icons.Filled.Search,
                                Tab.MAP to Icons.Filled.Place,
                                Tab.MORE to Icons.Filled.Shield,
                            )
                            items.forEach { (t, icon) ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(icon, contentDescription = t.label) },
                                    label = { Text(t.label, maxLines = 1) },
                                    colors = NavigationBarItemDefaults.colors(
                                        selectedIconColor = OkGreen,
                                        selectedTextColor = OkGreen,
                                        indicatorColor = OkGreen.copy(alpha = 0.14f),
                                        unselectedIconColor = ConsoleMuted,
                                        unselectedTextColor = ConsoleMuted,
                                    ),
                                )
                            }
                        }
                    }
                ) { padding ->
                    Column(Modifier.fillMaxSize().padding(padding)) {
                        Surface(
                            color = ConsoleBackground,
                            border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleOutline.copy(alpha = 0.7f)),
                        ) {
                            Row(
                                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 9.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                            ) {
                                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                                    Text("RF//WATCH", style = MaterialTheme.typography.labelSmall, color = OkGreen)
                                    Text("FIELD CONSOLE", style = MaterialTheme.typography.labelSmall, color = ConsoleMuted)
                                }
                                Surface(
                                    color = OkGreen.copy(alpha = 0.1f),
                                    shape = androidx.compose.foundation.shape.RoundedCornerShape(50),
                                    border = androidx.compose.foundation.BorderStroke(1.dp, OkGreen.copy(alpha = 0.35f)),
                                ) {
                                    Text(
                                        "●  PASSIVE RX",
                                        Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = OkGreen,
                                    )
                                }
                            }
                        }
                        if (!locationEnabled) {
                            LocationOffNotice {
                                startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
                            }
                        }
                        Box(Modifier.fillMaxWidth().weight(1f)) {
                            val contentModifier = Modifier.fillMaxSize()
                            when (tab) {
                                Tab.RADAR -> RadarScreen(vm, contentModifier)
                                Tab.DEVICES -> DevicesScreen(vm, contentModifier, onRoom = { tab = Tab.ROOM })
                                Tab.SWEEP -> SweepScreen(vm, contentModifier)
                                Tab.CELL -> CellScreen(vm, contentModifier)
                                Tab.ROOM -> RoomSweepScreen(vm, contentModifier)
                                Tab.MAP -> MapScreen(vm, contentModifier)
                                Tab.LOG -> EventLogScreen(vm, contentModifier)
                                Tab.SETTINGS -> SettingsScreen(contentModifier)
                                Tab.SKIMMER -> com.rfwatch.app.ui.screens.SkimmerScreen(contentModifier)
                                Tab.STALKERWARE -> com.rfwatch.app.ui.screens.StalkerwareScreen(contentModifier)
                                Tab.CAMERA_FINDER -> com.rfwatch.app.ui.screens.CameraFinderScreen(contentModifier)
                                Tab.ULTRASONIC -> com.rfwatch.app.ui.screens.UltrasonicScreen(vm, contentModifier)
                                Tab.CAR -> com.rfwatch.app.ui.screens.CarSweepScreen(vm, contentModifier) { id -> vm.huntRequest.value = id; tab = Tab.SWEEP }
                                Tab.MORE -> com.rfwatch.app.ui.screens.ProtectHub(vm, contentModifier) { dest ->
                                    tab = when (dest) {
                                        com.rfwatch.app.ui.screens.HubDest.STALKERWARE -> Tab.STALKERWARE
                                        com.rfwatch.app.ui.screens.HubDest.CAMERA_FINDER -> Tab.CAMERA_FINDER
                                        com.rfwatch.app.ui.screens.HubDest.ROOM -> Tab.ROOM
                                        com.rfwatch.app.ui.screens.HubDest.SKIMMER -> Tab.SKIMMER
                                        com.rfwatch.app.ui.screens.HubDest.CAR -> Tab.CAR
                                        com.rfwatch.app.ui.screens.HubDest.CELL -> Tab.CELL
                                        com.rfwatch.app.ui.screens.HubDest.LOG -> Tab.LOG
                                        com.rfwatch.app.ui.screens.HubDest.SETTINGS -> Tab.SETTINGS
                                        com.rfwatch.app.ui.screens.HubDest.ULTRASONIC -> Tab.ULTRASONIC
                                    }
                                }
                            }
                        }
                    }
                }
                    com.rfwatch.app.ui.AlertFlashOverlay((application as RfWatchApp).container.alertManager.flashes)
                }
                            }
            }
        }
    }

    private fun isLocationEnabled(): Boolean {
        val manager = getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            manager.isLocationEnabled
        } else {
            Settings.Secure.getInt(
                contentResolver,
                Settings.Secure.LOCATION_MODE,
                Settings.Secure.LOCATION_MODE_OFF,
            ) != Settings.Secure.LOCATION_MODE_OFF
        }
    }

    override fun onStart() {
        super.onStart()
        val d = com.rfwatch.app.discreet.Discreet
        if (backgroundAtMs > 0 && d.lockEnabled(this) &&
            (d.isDisguised(this) || System.currentTimeMillis() - backgroundAtMs > RELOCK_AFTER_MS)
        ) locked.value = true
        applySecureFlag()
    }

    override fun onStop() {
        super.onStop()
        backgroundAtMs = System.currentTimeMillis()
    }

    override fun onResume() {
        super.onResume()
        if (!com.rfwatch.app.discreet.Discreet.shakeToHide(this)) return
        val sm = getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val accel = sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER) ?: return
        var lastSpikeMs = 0L
        val listener = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val g = kotlin.math.sqrt(e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]) / 9.81f
                if (g < SHAKE_G) return
                val now = System.currentTimeMillis()
                if (now - lastSpikeMs in 120..800) hideNow() else lastSpikeMs = now
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) = Unit
        }
        sm.registerListener(listener, accel, android.hardware.SensorManager.SENSOR_DELAY_GAME)
        shakeListener = listener
    }

    override fun onPause() {
        super.onPause()
        shakeListener?.let { (getSystemService(Context.SENSOR_SERVICE) as android.hardware.SensorManager).unregisterListener(it) }
        shakeListener = null
    }

    /** Shake-to-hide: lock and remove the app from Recents immediately. */
    private fun hideNow() {
        if (com.rfwatch.app.discreet.Discreet.lockEnabled(this)) locked.value = true
        finishAndRemoveTask()
    }

    /** Block screenshots and Recents thumbnails while any discreet option is on. */
    private fun applySecureFlag() {
        val d = com.rfwatch.app.discreet.Discreet
        if (d.isDisguised(this) || d.neutralNotifications(this) || d.lockEnabled(this)) {
            window.setFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE, android.view.WindowManager.LayoutParams.FLAG_SECURE)
        } else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
    }

    /** Unlock with the phone's own PIN / pattern / password (or fingerprint where present). */
    private fun promptDeviceCredential() {
        val auth = androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK or
            androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
        if (androidx.biometric.BiometricManager.from(this).canAuthenticate(auth) != androidx.biometric.BiometricManager.BIOMETRIC_SUCCESS) return
        val prompt = androidx.biometric.BiometricPrompt(
            this, androidx.core.content.ContextCompat.getMainExecutor(this),
            object : androidx.biometric.BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: androidx.biometric.BiometricPrompt.AuthenticationResult) { locked.value = false }
            },
        )
        prompt.authenticate(
            androidx.biometric.BiometricPrompt.PromptInfo.Builder().setTitle("Unlock").setAllowedAuthenticators(auth).build(),
        )
    }

    private companion object {
        const val RELOCK_AFTER_MS = 30_000L
        const val SHAKE_G = 2.6f
    }

    private fun requiredPermissions(): Array<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.READ_PHONE_STATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_SCAN)
            add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            add(Manifest.permission.NEARBY_WIFI_DEVICES)
            add(Manifest.permission.POST_NOTIFICATIONS)
        }
    }.toTypedArray()
}

@androidx.compose.runtime.Composable
private fun LocationOffNotice(onEnable: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text("Location is off", style = MaterialTheme.typography.titleSmall)
                Text(
                    "Android may withhold fresh Wi-Fi scan results. Turn on Location to wardrive.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Button(onClick = onEnable) { Text("Enable") }
        }
    }
}
