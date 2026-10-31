package com.rfwatch.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
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
    RADAR("Radar"), DEVICES("Devices"), SWEEP("Sweep"), MAP("Map"), MORE("More"),
    CELL("Cell"), ROOM("Room"), LOG("Log"), SETTINGS("Settings"), SKIMMER("Skimmer")
}

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val repo = (application as RfWatchApp).container.repository

        setContent {
            RfWatchTheme {
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
                                Tab.MORE to Icons.Filled.MoreHoriz,
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
                                Tab.MORE -> MoreScreen(
                                    modifier = contentModifier,
                                    onCell = { tab = Tab.CELL },
                                    onRoom = { tab = Tab.ROOM },
                                    onLog = { tab = Tab.LOG },
                                    onSettings = { tab = Tab.SETTINGS },
                                    tools = listOf(
                                        Triple("Skimmer check", "Fuel pump / ATM card-skimmer Bluetooth check with full evidence.") { tab = Tab.SKIMMER },
                                    ),
                                )
                            }
                        }
                    }
                }
                    com.rfwatch.app.ui.AlertFlashOverlay((application as RfWatchApp).container.alertManager.flashes)
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
