package com.rfwatch.app

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.rfwatch.app.monitor.MonitorService
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.screens.CellScreen
import com.rfwatch.app.ui.screens.DevicesScreen
import com.rfwatch.app.ui.screens.EventLogScreen
import com.rfwatch.app.ui.screens.MapScreen
import com.rfwatch.app.ui.screens.RadarScreen
import com.rfwatch.app.ui.screens.RoomSweepScreen
import com.rfwatch.app.ui.screens.SettingsScreen
import com.rfwatch.app.ui.screens.SweepScreen
import com.rfwatch.app.ui.theme.RfWatchTheme

private enum class Tab(val label: String) {
    RADAR("Radar"), DEVICES("Devices"), SWEEP("Sweep"), CELL("Cell"),
    ROOM("Room"), MAP("Map"), LOG("Log"), SETTINGS("Settings")
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

                androidx.compose.runtime.LaunchedEffect(Unit) {
                    permLauncher.launch(requiredPermissions())
                }

                var tab by remember { mutableStateOf(Tab.RADAR) }

                Scaffold(
                    bottomBar = {
                        NavigationBar {
                            val items = listOf(
                                Tab.RADAR to Icons.Filled.LocationOn,
                                Tab.DEVICES to Icons.Filled.List,
                                Tab.SWEEP to Icons.Filled.Search,
                                Tab.CELL to Icons.Filled.CellTower,
                                Tab.MAP to Icons.Filled.Place,
                                Tab.LOG to Icons.Filled.Menu,
                                Tab.SETTINGS to Icons.Filled.Settings,
                            )
                            items.forEach { (t, icon) ->
                                NavigationBarItem(
                                    selected = tab == t,
                                    onClick = { tab = t },
                                    icon = { Icon(icon, contentDescription = t.label) },
                                    label = { Text(t.label) },
                                )
                            }
                        }
                    }
                ) { padding ->
                    val m = Modifier.padding(padding)
                    when (tab) {
                        Tab.RADAR -> RadarScreen(vm, m)
                        Tab.DEVICES -> DevicesScreen(vm, m, onRoom = { tab = Tab.ROOM })
                        Tab.SWEEP -> SweepScreen(vm, m)
                        Tab.CELL -> CellScreen(vm, m)
                        Tab.ROOM -> RoomSweepScreen(vm, m)
                        Tab.MAP -> MapScreen(vm, m)
                        Tab.LOG -> EventLogScreen(vm, m)
                        Tab.SETTINGS -> SettingsScreen(m)
                    }
                }
            }
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
