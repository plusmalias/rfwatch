package com.rfwatch.app.ui.screens

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rfwatch.app.BuildConfig
import com.rfwatch.app.data.DeviceEntity
import com.rfwatch.app.repo.DeviceSighting
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.agoText
import com.rfwatch.app.ui.friendlyName
import com.rfwatch.app.ui.isThreat
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.DangerRed
import com.rfwatch.app.ui.theme.RadarCyan
import com.rfwatch.app.ui.theme.RadarPink
import com.rfwatch.app.ui.theme.WarnAmber
import com.rfwatch.core.df.DriveByEstimator
import com.rfwatch.core.df.EmitterEstimate
import com.rfwatch.core.df.EstimateStatus
import com.rfwatch.core.id.DeviceKind
import com.rfwatch.core.model.GpsFix
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.HeatmapLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point
import java.util.Locale

private const val RADIO_SOURCE = "rfwatch-radio-source"
private const val RADIO_LABEL_SOURCE = "rfwatch-radio-label-source"
private const val HEAT_SOURCE = "rfwatch-heat-source"
private const val USER_SOURCE = "rfwatch-user-source"
private const val USER_LABEL_SOURCE = "rfwatch-user-label-source"
private const val HISTORY_SOURCE = "rfwatch-history-source"
private const val TRAIL_SOURCE = "rfwatch-trail-source"
private const val SELECTED_SOURCE = "rfwatch-selected-source"
private const val BASE_LAYER = "osm-raster"
private const val HEAT_LAYER = "rfwatch-heat"
private const val THREAT_HALO_LAYER = "rfwatch-threat-halo"
private const val RADIO_LAYER = "rfwatch-radio-points"
private const val CLUSTER_LAYER = "rfwatch-radio-clusters"
private const val CLUSTER_COUNT_LAYER = "rfwatch-cluster-count"
private const val STACK_COUNT_LAYER = "rfwatch-stack-count"
private const val NAME_LAYER = "rfwatch-device-names"
private const val TRAIL_LINE_LAYER = "rfwatch-trail-line"
private const val TRAIL_DOT_LAYER = "rfwatch-trail-dots"
private const val SELECTED_LAYER = "rfwatch-selected-ring"
private const val USER_ACC_LAYER = "rfwatch-user-accuracy"
private const val USER_LAYER = "rfwatch-user-point"
private const val USER_LABEL_LAYER = "rfwatch-user-label"
private const val HISTORY_LAYER = "rfwatch-history-points"
private const val RADIO_UPDATE_INTERVAL_MS = 1_000L
private const val NAV_ZOOM = 18.0

/** Time window of the map (by each device's last-heard time). */
private enum class MapWindow(val label: String, val ms: Long?) {
    LIVE("Live", 3 * 60_000L), HOUR("1 h", 3_600_000L), DAY("24 h", 86_400_000L), WEEK("7 d", 7 * 86_400_000L), ALL("All", null),
}

/** What the map shows. */
private enum class MapShow(val label: String) { ALL("Everything"), THREATS("Threats"), TRACKERS("Trackers"), DRONES("Drones"), WIFI("Wi-Fi"), BLE("Bluetooth") }

/**
 * ~10 m grid key. Phone GPS is ±10–20 m, so devices heard while standing in one place land on a
 * handful of jittered fixes; one marker per ~10 m cell reads as "heard here" instead of a pile.
 */
private fun stackKey(lat: Double, lon: Double) = "${Math.round(lat * 1e4)}:${Math.round(lon * 1e4)}"

private fun stackPriority(m: MappedDevice): Int = when {
    m.threat -> 5
    m.entity.classification == "TRACKER" -> 4
    m.entity.classification == "PRIORITY" -> 3
    m.entity.classification == "NOTABLE" -> 2
    m.estimate?.status == EstimateStatus.LOCATED -> 1
    else -> 0
}

// Bundled with the mapsOnline flavor (assets/glyphs, ASCII range only) so labels need no font host.
private val MAP_LABEL_FONT = arrayOf("Noto Sans Bold")

/** Map labels can only draw ASCII (bundled glyph range): drop anything else, keep it short. */
private fun asciiLabel(s: String): String =
    s.replace('·', '-').filter { it.code in 0x20..0x7E }.replace(Regex("\\s+"), " ").trim().take(26)

private data class MappedDevice(
    val entity: DeviceEntity,
    val latitude: Double,
    val longitude: Double,
    val estimate: EmitterEstimate?,
    val kind: DeviceKind,
    val threat: Boolean,
    val name: String,
) {
    val motionLabel: String get() = when (estimate?.status) {
        EstimateStatus.MOBILE -> "Moving"
        EstimateStatus.LOCATED -> "Position estimated"
        else -> "Where you heard it"
    }
    val locationBasis: String get() = when (estimate?.status) {
        EstimateStatus.LOCATED -> "Drive-by fit · ±${estimate.ellipse?.semiMajorM?.toInt() ?: "?"} m error"
        EstimateStatus.MOBILE -> "Last phone GPS point where signal was heard; device position unknown"
        else -> "Pin = your phone's GPS when it was last heard · " +
            (estimate?.reason?.let { "not located: $it" } ?: "walk/drive past it to estimate its own position")
    }
    val markerColor: String get() = colorHex(deviceCategoryColor(entity.category, entity.classification))
    val motionColor: String get() = when (estimate?.status) {
        EstimateStatus.MOBILE -> "#F59E0B"
        EstimateStatus.LOCATED -> "#34D399"
        else -> "#CBD5E1"
    }
}

private fun MapShow.accepts(m: MappedDevice): Boolean = when (this) {
    MapShow.ALL -> true
    MapShow.THREATS -> m.threat
    MapShow.TRACKERS -> m.kind == DeviceKind.TRACKER || m.entity.classification == "TRACKER"
    MapShow.DRONES -> m.kind == DeviceKind.DRONE
    MapShow.WIFI -> m.entity.band == "WIFI"
    MapShow.BLE -> m.entity.band == "BLE"
}

@Composable
fun MapScreen(vm: RfWatchViewModel, modifier: Modifier = Modifier) {
    val allDevices by vm.devices.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val userLocation by vm.userLocation.collectAsState()
    // Shared filter: optionally show only what the Devices tab's filters let through.
    val useDeviceFilter by vm.mapUsesDeviceFilter.collectAsState()
    val filteredView by vm.deviceView.collectAsState()
    val devices = if (useDeviceFilter) filteredView.visible else allDevices
    if (BuildConfig.FLAVOR == "mapsOnline") {
        OnlineDeviceMap(devices, contacts, userLocation, vm, modifier)
    } else {
        val events by vm.events.collectAsState()
        val nav by vm.deviceNav.collectAsState()
        LaunchedEffect(nav) { nav?.takeIf { it.dest == com.rfwatch.app.ui.DeviceDest.MAP }?.let { vm.consumeNav(it) } }
        OfflineDeviceMap(devices, events, modifier)
    }
}

@Composable
private fun OnlineDeviceMap(
    devices: List<DeviceEntity>,
    contacts: Map<String, com.rfwatch.app.repo.LiveContact>,
    userLocation: GpsFix?,
    vm: RfWatchViewModel,
    modifier: Modifier,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    // Texture mode composes correctly above/below Compose controls (SurfaceView can cover
    // them on older Android GPUs and several vendor ROMs).
    val mapView = remember(context) {
        MapView(context, MapLibreMapOptions().textureMode(true).attributionEnabled(false)).also { it.onCreate(null) }
    }
    val mapRef = remember { mutableStateOf<MapLibreMap?>(null) }
    val styleReady = remember { mutableStateOf(false) }
    val cameraPositioned = remember { mutableStateOf(false) }
    var selectedId by remember { mutableStateOf<String?>(null) }
    var selectedStack by remember { mutableStateOf<String?>(null) }
    var mapError by remember { mutableStateOf(false) }
    val events by vm.events.collectAsState()
    var showHistory by rememberSaveable { mutableStateOf(false) }
    var showMapHelp by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<com.rfwatch.app.data.EventEntity?>(null) }
    val latestEvents by rememberUpdatedState(events)
    var window by rememberSaveable { mutableStateOf(MapWindow.DAY) }
    var show by rememberSaveable { mutableStateOf(MapShow.ALL) }
    var darkBase by rememberSaveable { mutableStateOf(true) }
    var heat by rememberSaveable { mutableStateOf(false) }
    var trail by remember { mutableStateOf<List<DeviceSighting>>(emptyList()) }
    val now = remember(devices) { System.currentTimeMillis() }

    // Everything with a position, before the time / type chips (counts for the chips use it).
    val positioned = remember(devices, contacts) {
        devices.mapNotNull { device ->
            val estimate = contacts[device.id]?.estimate
            val lat = if (estimate?.status == EstimateStatus.LOCATED) estimate.lat else device.observedLat
            val lon = if (estimate?.status == EstimateStatus.LOCATED) estimate.lon else device.observedLon
            if (lat == null || lon == null) null else {
                val kind = device.effectiveKind()
                MappedDevice(
                    device, lat, lon, estimate, kind, isThreat(device, kind),
                    friendlyName(device.nickname, device.displayName, device.brand, kind, device.id, device.model),
                )
            }
        }
    }
    val inWindow = remember(positioned, window, now) {
        val start = window.ms?.let { now - it }
        if (start == null) positioned else positioned.filter { it.entity.lastSeenMs >= start }
    }
    val mapped = remember(inWindow, show) { inWindow.filter { show.accepts(it) } }
    // Devices heard from the same phone fix share exact coordinates; draw them as one numbered
    // marker (measured on-device: 166 devices on 23 points, up to 29 on one spot).
    val stacks = remember(mapped) { mapped.groupBy { stackKey(it.latitude, it.longitude) } }
    val movingCount = mapped.count { it.estimate?.status == EstimateStatus.MOBILE }
    val locatedCount = mapped.count { it.estimate?.status == EstimateStatus.LOCATED }
    val threatCount = mapped.count { it.threat }
    val selected = positioned.firstOrNull { it.entity.id == selectedId }
    val deviceFilter by vm.deviceFilter.collectAsState()
    val useDeviceFilter by vm.mapUsesDeviceFilter.collectAsState()
    var navNotice by remember { mutableStateOf<String?>(null) }
    val nav by vm.deviceNav.collectAsState()
    LaunchedEffect(nav, styleReady.value) {
        val n = nav ?: return@LaunchedEffect
        if (n.dest != com.rfwatch.app.ui.DeviceDest.MAP || !styleReady.value) return@LaunchedEffect
        vm.consumeNav(n)
        // The first-open auto-centre (on YOU) must not run after this, or it overrides the jump.
        cameraPositioned.value = true
        showHistory = false
        // A device hidden by the chips or the shared filter is still shown: widen rather than fail.
        if (mapped.none { it.entity.id == n.deviceId }) {
            window = MapWindow.ALL; show = MapShow.ALL
            if (useDeviceFilter && filteredView(vm).none { it.id == n.deviceId }) vm.mapUsesDeviceFilter.value = false
        }
        val target = vm.devices.value.firstOrNull { it.id == n.deviceId }
        val est = contacts[n.deviceId]?.estimate
        val lat = if (est?.status == EstimateStatus.LOCATED) est.lat else target?.observedLat
        val lon = if (est?.status == EstimateStatus.LOCATED) est.lon else target?.observedLon
        if (lat != null && lon != null) {
            selectedId = n.deviceId
            selectedStack = null
            navNotice = null
            // Past the cluster max zoom (17) so the device is its own marker, not inside a group.
            focusAboveCard(mapRef.value, mapView, LatLng(lat, lon), NAV_ZOOM)
        } else {
            navNotice = "${target?.let { friendlyName(it.nickname, it.displayName, it.brand, it.effectiveKind(), it.id, it.model) } ?: n.deviceId}: " +
                "no GPS-tagged sighting yet — keep Location on while scanning."
        }
    }

    // Selected device: its own sighting trail (where your phone heard it, in time order).
    LaunchedEffect(selectedId) {
        val id = selectedId
        trail = if (id == null) emptyList() else runCatching { vm.locateData(id).sightings }.getOrDefault(emptyList())
    }

    // Direction-finding fits update while this screen is visible. Only a bounded set of
    // recent GPS histories are evaluated; under-sampled emitters remain explicitly unknown.
    LaunchedEffect(vm) { if (BuildConfig.DEBUG) { vm.logDuplicateReport(); vm.logIdReport() } }
    LaunchedEffect(vm) {
        while (true) {
            vm.refreshMapEstimates()
            delay(15_000L)
        }
    }

    DisposableEffect(mapView, lifecycleOwner) {
        mapView.onStart()
        mapView.onResume()
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> mapView.onStart()
                Lifecycle.Event.ON_RESUME -> mapView.onResume()
                Lifecycle.Event.ON_PAUSE -> mapView.onPause()
                Lifecycle.Event.ON_STOP -> mapView.onStop()
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            mapView.onPause()
            mapView.onStop()
            mapView.onDestroy()
            mapRef.value = null
            styleReady.value = false
        }
    }

    LaunchedEffect(mapView) {
        mapView.addOnDidFailLoadingMapListener {
            android.util.Log.e("RFWatchMap", "MapLibre load failed: $it")
            mapError = true
        }
        mapView.getMapAsync { map ->
            mapRef.value = map
            map.setPrefetchesTiles(false)
            map.setTileCacheEnabled(true)
            map.uiSettings.isAttributionEnabled = false // a visible attribution chip is drawn below.
            map.uiSettings.isLogoEnabled = false // it sat on top of the OSM attribution text.
            map.uiSettings.setCompassMargins(0, 220, 18, 0)
            map.setStyle(Style.Builder().fromJson(OSM_RASTER_STYLE)) { style ->
                addRadioLayers(style)
                styleReady.value = true
                mapError = false
            }
            map.addOnMapClickListener { latLng ->
                val pixel = map.projection.toScreenLocation(latLng)
                map.queryRenderedFeatures(pixel, HISTORY_LAYER).firstOrNull()?.let { h ->
                    val rowId = h.getNumberProperty("rowId")?.toLong()
                    selectedEvent = latestEvents.firstOrNull { it.rowId == rowId }
                    return@addOnMapClickListener true
                }
                val hit = map.queryRenderedFeatures(pixel, RADIO_LAYER, CLUSTER_LAYER).firstOrNull()
                if (hit == null) {
                    selectedId = null
                    selectedStack = null
                } else if (hit.hasNonNullValueForProperty("point_count")) {
                    val source = map.style?.getSource(RADIO_SOURCE) as? GeoJsonSource
                    val zoom = source?.getClusterExpansionZoom(hit)?.toDouble() ?: (map.cameraPosition.zoom + 1.0)
                    val point = hit.geometry() as? Point
                    if (point != null) map.easeCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude(), point.longitude()), zoom), 250,
                    )
                    selectedId = null
                    selectedStack = null
                } else if ((hit.getNumberProperty("devices")?.toInt() ?: 1) > 1) {
                    selectedStack = hit.getStringProperty("stack")
                    selectedId = null
                } else {
                    selectedId = hit.getStringProperty("deviceId")
                    selectedStack = null
                }
                true
            }
        }
    }

    // Devices/contacts re-emit several times a second while scanning. Keying an effect on
    // `mapped` with a delay inside restarts (cancels) it on every emission, so it never
    // reaches setGeoJson. Instead collect the latest value, conflated: the first update is
    // immediate and later ones are applied at most once per RADIO_UPDATE_INTERVAL_MS.
    val latestMapped by rememberUpdatedState(mapped)
    LaunchedEffect(styleReady.value) {
        if (!styleReady.value) return@LaunchedEffect
        snapshotFlow { latestMapped }.conflate().collect { current ->
            val features = current.groupBy { stackKey(it.latitude, it.longitude) }.map { (key, group) ->
                // The most notable device in the stack sets its colour; size/label show the count.
                val item = group.maxBy { stackPriority(it) }
                // Single devices keep their own point; a stack sits at its members' centre.
                val lat = if (group.size == 1) item.latitude else group.sumOf { it.latitude } / group.size
                val lon = if (group.size == 1) item.longitude else group.sumOf { it.longitude } / group.size
                Feature.fromGeometry(Point.fromLngLat(lon, lat)).apply {
                    addStringProperty("stack", key)
                    addNumberProperty("devices", group.size)
                    addNumberProperty("threats", group.count { it.threat })
                    addStringProperty("deviceId", item.entity.id)
                    addStringProperty("label", asciiLabel(if (group.size == 1) item.name else "${group.size} devices"))
                    addStringProperty("markerColor", item.markerColor)
                    addStringProperty("motionColor", item.motionColor)
                }
            }
            val data = FeatureCollection.fromFeatures(features)
            mapRef.value?.getStyle { style ->
                (style.getSource(RADIO_SOURCE) as? GeoJsonSource)?.setGeoJson(data)
                (style.getSource(RADIO_LABEL_SOURCE) as? GeoJsonSource)?.setGeoJson(data)
                (style.getSource(HEAT_SOURCE) as? GeoJsonSource)?.setGeoJson(data)
            }
            delay(RADIO_UPDATE_INTERVAL_MS)
        }
    }

    LaunchedEffect(userLocation, styleReady.value) {
        if (!styleReady.value) return@LaunchedEffect
        val collection = userLocation?.let { fix ->
            FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(fix.lon, fix.lat)))
        } ?: FeatureCollection.fromFeatures(emptyArray<Feature>())
        mapRef.value?.getStyle { style ->
            (style.getSource(USER_SOURCE) as? GeoJsonSource)?.setGeoJson(collection)
            (style.getSource(USER_LABEL_SOURCE) as? GeoJsonSource)?.setGeoJson(collection)
        }
    }

    // Selection ring + trail of where the phone heard the selected device.
    LaunchedEffect(styleReady.value, selected?.entity?.id, selected?.latitude, selected?.longitude, trail) {
        if (!styleReady.value) return@LaunchedEffect
        val ring = selected?.let { FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(it.longitude, it.latitude))) }
            ?: FeatureCollection.fromFeatures(emptyArray<Feature>())
        val ordered = trail.sortedBy { it.timestampMs }
        val trailFeatures = buildList {
            if (ordered.size >= 2) add(Feature.fromGeometry(LineString.fromLngLats(ordered.map { Point.fromLngLat(it.lon, it.lat) })))
            ordered.forEach { s ->
                add(Feature.fromGeometry(Point.fromLngLat(s.lon, s.lat)).apply {
                    addNumberProperty("rssi", s.rssi)
                    addBooleanProperty("dot", true)
                })
            }
        }
        mapRef.value?.getStyle { style ->
            (style.getSource(SELECTED_SOURCE) as? GeoJsonSource)?.setGeoJson(ring)
            (style.getSource(TRAIL_SOURCE) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(trailFeatures))
        }
    }

    LaunchedEffect(styleReady.value, userLocation, mapped.isEmpty()) {
        if (!styleReady.value || cameraPositioned.value) return@LaunchedEffect
        val location = userLocation?.let { LatLng(it.lat, it.lon) }
            ?: mapped.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
        if (location != null) {
            mapRef.value?.animateCamera(CameraUpdateFactory.newLatLngZoom(location, if (userLocation != null) 15.5 else 13.0), 350)
            cameraPositioned.value = true
        }
    }

    // Basemap look: the OSM raster inverted + hue-rotated reads as a dark console map.
    LaunchedEffect(styleReady.value, darkBase) {
        if (!styleReady.value) return@LaunchedEffect
        mapRef.value?.getStyle { style -> style.getLayer(BASE_LAYER)?.setProperties(*basemapPaint(darkBase)) }
    }

    // Layer visibility: history replaces device markers; heat replaces clusters/points.
    LaunchedEffect(styleReady.value, showHistory, heat, events) {
        if (!styleReady.value) return@LaunchedEffect
        val features = if (!showHistory) emptyList() else events.filter { it.isMappableHistory() }.map { e ->
            Feature.fromGeometry(Point.fromLngLat(e.lon!!, e.lat!!)).apply {
                addNumberProperty("rowId", e.rowId)
                addStringProperty("color", HISTORY_KINDS[e.kind]?.first ?: "#94A3B8")
            }
        }
        mapRef.value?.getStyle { style ->
            (style.getSource(HISTORY_SOURCE) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(features))
            fun vis(on: Boolean) = PropertyFactory.visibility(if (on) Property.VISIBLE else Property.NONE)
            val markers = !showHistory && !heat
            listOf(RADIO_LAYER, CLUSTER_LAYER, CLUSTER_COUNT_LAYER, STACK_COUNT_LAYER, THREAT_HALO_LAYER, NAME_LAYER)
                .forEach { style.getLayer(it)?.setProperties(vis(markers)) }
            style.getLayer(HEAT_LAYER)?.setProperties(vis(!showHistory && heat))
            listOf(TRAIL_LINE_LAYER, TRAIL_DOT_LAYER, SELECTED_LAYER).forEach { style.getLayer(it)?.setProperties(vis(!showHistory)) }
        }
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 6.dp)
            .then(if (showHistory) Modifier.verticalScroll(rememberScrollState()) else Modifier),
    ) {
        // Row 1: view switch + shared filter + help.
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            MapChip("Devices", !showHistory) { showHistory = false }
            MapChip("History ${events.count { it.isMappableHistory() }}", showHistory) { showHistory = true; selectedId = null; selectedStack = null }
            if (!showHistory) MapChip(if (useDeviceFilter && !deviceFilter.isDefault) "Devices filters ✓" else "Devices filters", useDeviceFilter) {
                vm.mapUsesDeviceFilter.value = !useDeviceFilter
            }
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { showMapHelp = !showMapHelp }) { Text(if (showMapHelp) "Close" else "ⓘ", fontSize = 14.sp) }
        }
        if (!showHistory) {
            // Row 2: time window and type, one scrollable strip; counts reflect the time window.
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            ) {
                MapWindow.entries.forEach { w -> MapChip(w.label, window == w, small = true) { window = w } }
                Box(Modifier.padding(horizontal = 2.dp).size(1.dp, 22.dp).background(ConsoleOutline))
                MapShow.entries.forEach { s ->
                    val n = if (s == MapShow.ALL) inWindow.size else inWindow.count { s.accepts(it) }
                    if (s == MapShow.ALL || n > 0 || show == s) MapChip(
                        if (s == MapShow.ALL) s.label else "${s.label} $n", show == s, small = true,
                        accent = if (s == MapShow.THREATS) DangerRed else if (s == MapShow.DRONES) WarnAmber else RadarCyan,
                    ) { show = s }
                }
            }
        }
        // Summary: one headline, one quiet detail line.
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (showHistory) "Encounter history" else "${"%,d".format(Locale.US, mapped.size)} devices · ${stacks.size} spots",
                fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1,
            )
            if (!showHistory && threatCount > 0) Text(
                "  $threatCount to check", fontSize = 13.sp, color = DangerRed, fontWeight = FontWeight.SemiBold, maxLines = 1,
                modifier = Modifier.clickable { show = MapShow.THREATS },
            )
        }
        if (!showHistory) Text(
            listOfNotNull(
                "${window.label} · ${show.label}",
                locatedCount.takeIf { it > 0 }?.let { "$it position estimated" },
                movingCount.takeIf { it > 0 }?.let { "$it moving" },
                "tap a marker for its card and trail",
            ).joinToString(" · "),
            fontSize = 10.sp, color = ConsoleMuted, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(bottom = 4.dp),
        )
        navNotice?.let { Text(it, fontSize = 11.sp, color = WarnAmber) }
        if (showMapHelp) Text(
            "Fill colour = device category; red halo = worth checking. Outline: green = position estimated · amber = moving · grey = drawn where " +
                "your phone heard it (not the device). Estimates need ≥${DriveByEstimator.MIN_SAMPLES} GPS-tagged hearings spread over " +
                "≥${DriveByEstimator.MIN_SPREAD_M.toInt()} m — walk or drive past. Circles with numbers are groups (red = contains a threat): tap " +
                "to zoom in. Tap a device for its card and its trail (every place your phone heard it). Heat shows where the most devices were " +
                "heard. Online tiles reveal the visible area to OpenStreetMap; radio records stay on this phone.",
            fontSize = 10.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(bottom = 5.dp),
        )
        Box(
            (if (showHistory) Modifier.fillMaxWidth().height(380.dp) else Modifier.fillMaxWidth().weight(1f))
                .clip(RoundedCornerShape(12.dp)),
        ) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                MapOverlayButton("◎ Me", enabled = userLocation != null) {
                    userLocation?.let { fix ->
                        mapRef.value?.animateCamera(CameraUpdateFactory.newCameraPosition(
                            org.maplibre.android.camera.CameraPosition.Builder().target(LatLng(fix.lat, fix.lon)).zoom(16.5).padding(0.0, 0.0, 0.0, 0.0).build(),
                        ), 350)
                    }
                }
                MapOverlayButton("⤢ Fit", enabled = mapped.isNotEmpty() || userLocation != null) { fitMapToDevices(mapRef.value, mapped, userLocation) }
                if (!showHistory) MapOverlayButton(if (heat) "● Heat" else "○ Heat", enabled = true, active = heat) { heat = !heat }
                MapOverlayButton(if (darkBase) "Dark" else "Street", enabled = true) { darkBase = !darkBase }
            }
            if (mapError) {
                Card(Modifier.align(Alignment.Center).padding(20.dp), colors = CardDefaults.cardColors(containerColor = ConsolePanel), border = BorderStroke(1.dp, ConsoleOutline)) {
                    Text("Map tiles unavailable. Check internet and retry by reopening Map.", Modifier.padding(12.dp), fontSize = 12.sp)
                }
            } else if (mapped.isEmpty() && !showHistory) {
                Card(Modifier.align(Alignment.TopCenter).padding(8.dp), colors = CardDefaults.cardColors(containerColor = ConsolePanel.copy(alpha = 0.94f)), border = BorderStroke(1.dp, ConsoleOutline)) {
                    Text(
                        if (positioned.isEmpty()) "Waiting for GPS-tagged detections" else "Nothing matches ${window.label} · ${show.label} — widen the chips above",
                        Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 11.sp,
                    )
                }
            }
            Text(
                "© OpenStreetMap contributors",
                Modifier.align(Alignment.BottomEnd).padding(end = 8.dp, bottom = 5.dp)
                    .background(Color(0x99000000), RoundedCornerShape(4.dp)).padding(horizontal = 4.dp, vertical = 1.dp),
                fontSize = 9.sp, color = Color(0xFFCBD5E1),
            )
            // Floating cards over the bottom of the map keep the map full-height.
            if (!showHistory) {
                val cardModifier = Modifier.align(Alignment.BottomCenter).padding(8.dp).fillMaxWidth()
                if (selected != null) {
                    MapDeviceCard(selected, trail, vm, cardModifier) { selectedId = null }
                } else selectedStack?.let { stacks[it] }?.let { group ->
                    MapStackCard(group, cardModifier, onClose = { selectedStack = null }) { id ->
                        selectedId = id
                        group.firstOrNull { it.entity.id == id }?.let { m ->
                            focusAboveCard(mapRef.value, mapView, LatLng(m.latitude, m.longitude), maxOf(mapRef.value?.cameraPosition?.zoom ?: NAV_ZOOM, NAV_ZOOM))
                        }
                    }
                }
            }
        }
        if (showHistory) {
            HistoryPanel(events, selectedEvent) { e ->
                selectedEvent = e
                if (e.lat != null && e.lon != null) mapRef.value?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(e.lat!!, e.lon!!), 16.0), 350)
            }
        }
    }
}

/**
 * Centres [target] in the part of the map the floating device card does not cover: bottom
 * padding of half the map's height lifts it into the upper half.
 */
private fun focusAboveCard(map: MapLibreMap?, view: MapView, target: LatLng, zoom: Double) {
    map ?: return
    val bottom = view.height * 0.5
    map.animateCamera(
        CameraUpdateFactory.newCameraPosition(
            org.maplibre.android.camera.CameraPosition.Builder().target(target).zoom(zoom).padding(0.0, 0.0, 0.0, bottom).build(),
        ),
        450,
    )
}

/** Devices the shared Devices filter currently lets through. */
private fun filteredView(vm: RfWatchViewModel): List<DeviceEntity> = vm.deviceView.value.visible

/** Every source and layer, bottom to top: heat, trail, threat halo, clusters, points, labels, selection, you. */
private fun addRadioLayers(style: Style) {
    val empty = FeatureCollection.fromFeatures(emptyArray<Feature>())
    // Clusters sum the per-marker device and threat counts, so totals stay in devices.
    val clusterOptions = {
        GeoJsonOptions().withCluster(true).withClusterRadius(44).withClusterMaxZoom(17)
            .withClusterProperty("devices", Expression.sum(Expression.accumulated(), Expression.get("devices")), Expression.get("devices"))
            .withClusterProperty("threats", Expression.sum(Expression.accumulated(), Expression.get("threats")), Expression.get("threats"))
    }
    // Text labels live in their own sources. A symbol layer makes MapLibre wait for glyphs
    // before it finishes a source's tiles; sharing a source with the circle layers meant a
    // missing/slow glyph range hid every marker.
    style.addSource(GeoJsonSource(RADIO_SOURCE, empty, clusterOptions()))
    style.addSource(GeoJsonSource(RADIO_LABEL_SOURCE, empty, clusterOptions()))
    style.addSource(GeoJsonSource(HEAT_SOURCE, empty))
    style.addSource(GeoJsonSource(TRAIL_SOURCE, empty))
    style.addSource(GeoJsonSource(SELECTED_SOURCE, empty))
    style.addSource(GeoJsonSource(USER_SOURCE, empty))
    style.addSource(GeoJsonSource(USER_LABEL_SOURCE, empty))
    style.addSource(GeoJsonSource(HISTORY_SOURCE, empty))

    style.addLayer(
        HeatmapLayer(HEAT_LAYER, HEAT_SOURCE).withProperties(
            PropertyFactory.heatmapWeight(Expression.interpolate(Expression.linear(), Expression.get("devices"), Expression.stop(1, 0.25), Expression.stop(30, 1))),
            PropertyFactory.heatmapIntensity(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 0.6), Expression.stop(17, 1.6))),
            PropertyFactory.heatmapRadius(Expression.interpolate(Expression.linear(), Expression.zoom(), Expression.stop(10, 10), Expression.stop(17, 34))),
            PropertyFactory.heatmapOpacity(0.85f),
            PropertyFactory.heatmapColor(
                Expression.interpolate(
                    Expression.linear(), Expression.heatmapDensity(),
                    Expression.stop(0, Expression.rgba(0, 0, 0, 0)),
                    Expression.stop(0.2, Expression.rgba(56, 189, 248, 0.55)),
                    Expression.stop(0.45, Expression.rgba(77, 226, 197, 0.75)),
                    Expression.stop(0.7, Expression.rgba(255, 198, 109, 0.85)),
                    Expression.stop(1, Expression.rgba(255, 101, 119, 0.95)),
                ),
            ),
            PropertyFactory.visibility(Property.NONE),
        ),
    )
    style.addLayer(
        LineLayer(TRAIL_LINE_LAYER, TRAIL_SOURCE).withFilter(Expression.not(Expression.has("dot"))).withProperties(
            PropertyFactory.lineColor("#4DE2C5"), PropertyFactory.lineWidth(2.5f), PropertyFactory.lineOpacity(0.8f),
            PropertyFactory.lineDasharray(arrayOf(2f, 1.5f)),
        ),
    )
    style.addLayer(
        CircleLayer(TRAIL_DOT_LAYER, TRAIL_SOURCE).withFilter(Expression.has("dot")).withProperties(
            // Stronger signal = brighter, larger dot: the device was closest there.
            PropertyFactory.circleRadius(Expression.interpolate(Expression.linear(), Expression.get("rssi"), Expression.stop(-100, 3), Expression.stop(-50, 7))),
            PropertyFactory.circleColor(
                Expression.interpolate(
                    Expression.linear(), Expression.get("rssi"),
                    Expression.stop(-100, Expression.rgb(56, 189, 248)), Expression.stop(-75, Expression.rgb(77, 226, 197)),
                    Expression.stop(-55, Expression.rgb(255, 101, 119)),
                ),
            ),
            PropertyFactory.circleStrokeColor("#0B1116"), PropertyFactory.circleStrokeWidth(1f),
        ),
    )
    val clustered = Expression.has("point_count")
    style.addLayer(
        CircleLayer(THREAT_HALO_LAYER, RADIO_SOURCE).withFilter(Expression.gt(Expression.get("threats"), Expression.literal(0))).withProperties(
            PropertyFactory.circleColor("#FF6577"), PropertyFactory.circleOpacity(0.28f),
            PropertyFactory.circleRadius(Expression.switchCase(clustered, Expression.literal(30f), Expression.literal(17f))),
        ),
    )
    style.addLayer(
        CircleLayer(CLUSTER_LAYER, RADIO_SOURCE).withFilter(clustered).withProperties(
            PropertyFactory.circleColor(
                Expression.switchCase(Expression.gt(Expression.get("threats"), Expression.literal(0)), Expression.literal("#FF6577"), Expression.literal("#38BDF8")),
            ),
            PropertyFactory.circleOpacity(0.92f),
            PropertyFactory.circleRadius(
                Expression.step(
                    Expression.get("devices"), Expression.literal(14),
                    Expression.stop(10, Expression.literal(18)), Expression.stop(50, Expression.literal(23)), Expression.stop(500, Expression.literal(28)),
                ),
            ),
            PropertyFactory.circleStrokeColor("#F8FAFC"), PropertyFactory.circleStrokeWidth(1.5f),
        ),
    )
    style.addLayer(
        CircleLayer(RADIO_LAYER, RADIO_SOURCE).withFilter(Expression.not(clustered)).withProperties(
            PropertyFactory.circleColor(Expression.toColor(Expression.get("markerColor"))),
            PropertyFactory.circleRadius(Expression.step(Expression.get("devices"), Expression.literal(7f), Expression.stop(2, Expression.literal(11f)))),
            PropertyFactory.circleStrokeColor(Expression.toColor(Expression.get("motionColor"))),
            PropertyFactory.circleStrokeWidth(2.5f),
        ),
    )
    style.addLayer(
        CircleLayer(SELECTED_LAYER, SELECTED_SOURCE).withProperties(
            PropertyFactory.circleRadius(19f), PropertyFactory.circleColor("#4DE2C5"), PropertyFactory.circleOpacity(0.18f),
            PropertyFactory.circleStrokeColor("#4DE2C5"), PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    style.addLayer(
        CircleLayer(USER_ACC_LAYER, USER_SOURCE).withProperties(
            PropertyFactory.circleRadius(22f), PropertyFactory.circleColor("#38BDF8"), PropertyFactory.circleOpacity(0.18f),
        ),
    )
    style.addLayer(
        CircleLayer(USER_LAYER, USER_SOURCE).withProperties(
            PropertyFactory.circleColor("#38BDF8"), PropertyFactory.circleRadius(8f),
            PropertyFactory.circleStrokeColor("#F8FAFC"), PropertyFactory.circleStrokeWidth(3f),
        ),
    )
    style.addLayer(
        SymbolLayer(CLUSTER_COUNT_LAYER, RADIO_LABEL_SOURCE).withFilter(clustered).withProperties(
            PropertyFactory.textField(Expression.toString(Expression.get("devices"))), PropertyFactory.textFont(MAP_LABEL_FONT), PropertyFactory.textSize(11f),
            PropertyFactory.textColor("#08212A"), PropertyFactory.textHaloColor("#E0F7FA"), PropertyFactory.textHaloWidth(0.5f),
            PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
        ),
    )
    style.addLayer(
        SymbolLayer(STACK_COUNT_LAYER, RADIO_LABEL_SOURCE)
            .withFilter(Expression.all(Expression.not(clustered), Expression.gt(Expression.get("devices"), Expression.literal(1))))
            .withProperties(
                PropertyFactory.textField(Expression.toString(Expression.get("devices"))), PropertyFactory.textFont(MAP_LABEL_FONT), PropertyFactory.textSize(10f),
                PropertyFactory.textColor("#08212A"), PropertyFactory.textHaloColor("#F8FAFC"), PropertyFactory.textHaloWidth(1f),
                PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
            ),
    )
    // Names under single markers once zoomed in to street level (placement avoids overlaps).
    style.addLayer(
        SymbolLayer(NAME_LAYER, RADIO_LABEL_SOURCE)
            .withFilter(Expression.all(Expression.not(clustered), Expression.eq(Expression.get("devices"), Expression.literal(1))))
            .withProperties(
            PropertyFactory.textField(Expression.get("label")), PropertyFactory.textFont(MAP_LABEL_FONT), PropertyFactory.textSize(10f),
            PropertyFactory.textColor("#E2E8F0"), PropertyFactory.textHaloColor("#070B10"), PropertyFactory.textHaloWidth(1.4f),
            PropertyFactory.textOffset(arrayOf(0f, 1.3f)), PropertyFactory.textAnchor(Property.TEXT_ANCHOR_TOP),
            PropertyFactory.textMaxWidth(9f), PropertyFactory.textOptional(true),
        ).also { it.minZoom = 16.5f },
    )
    style.addLayer(
        SymbolLayer(USER_LABEL_LAYER, USER_LABEL_SOURCE).withProperties(
            PropertyFactory.textField("YOU"), PropertyFactory.textFont(MAP_LABEL_FONT), PropertyFactory.textSize(10f), PropertyFactory.textColor("#08212A"),
            PropertyFactory.textHaloColor("#F8FAFC"), PropertyFactory.textHaloWidth(1.2f), PropertyFactory.textOffset(arrayOf(0f, 3.2f)),
            PropertyFactory.textAllowOverlap(true), PropertyFactory.textIgnorePlacement(true),
        ),
    )
    style.addLayer(
        CircleLayer(HISTORY_LAYER, HISTORY_SOURCE).withProperties(
            PropertyFactory.circleColor(Expression.toColor(Expression.get("color"))), PropertyFactory.circleRadius(9f),
            PropertyFactory.circleStrokeColor("#FFFFFF"), PropertyFactory.circleStrokeWidth(2f),
        ),
    )
}

private fun basemapPaint(dark: Boolean): Array<org.maplibre.android.style.layers.PropertyValue<*>> = if (dark) arrayOf(
    PropertyFactory.rasterBrightnessMin(1f), PropertyFactory.rasterBrightnessMax(0.08f),
    PropertyFactory.rasterHueRotate(180f), PropertyFactory.rasterSaturation(-0.45f), PropertyFactory.rasterContrast(0.12f),
) else arrayOf(
    PropertyFactory.rasterBrightnessMin(0f), PropertyFactory.rasterBrightnessMax(1f),
    PropertyFactory.rasterHueRotate(0f), PropertyFactory.rasterSaturation(0f), PropertyFactory.rasterContrast(0f),
)

@Composable
private fun MapChip(label: String, selected: Boolean, small: Boolean = false, accent: Color = RadarCyan, onClick: () -> Unit) {
    FilterChip(
        selected = selected, onClick = onClick,
        label = { Text(label, fontSize = if (small) 12.sp else 13.sp, maxLines = 1) },
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = accent.copy(alpha = 0.22f), selectedLabelColor = accent,
        ),
        modifier = if (small) Modifier.height(30.dp) else Modifier,
    )
}

/** The selected device: who it is, how sure, where/when it was heard, and every way to follow up. */
@Composable
private fun MapDeviceCard(m: MappedDevice, trail: List<DeviceSighting>, vm: RfWatchViewModel, modifier: Modifier, onClose: () -> Unit) {
    val d = m.entity
    val color = deviceCategoryColor(d.category, d.classification)
    Card(
        modifier, colors = CardDefaults.cardColors(containerColor = ConsolePanel.copy(alpha = 0.97f)),
        border = BorderStroke(1.dp, if (m.threat) DangerRed.copy(alpha = 0.8f) else ConsoleOutline),
    ) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(34.dp).clip(CircleShape).background(color.copy(alpha = 0.18f)), contentAlignment = Alignment.Center) {
                    Icon(if (m.kind == DeviceKind.UNKNOWN && d.band == "WIFI") Icons.Filled.Wifi else kindIcon(m.kind), null, tint = color, modifier = Modifier.size(20.dp))
                }
                Column(Modifier.weight(1f).padding(start = 10.dp)) {
                    Text(m.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        listOfNotNull(d.brand, d.model ?: m.kind.label, if (d.band == "WIFI") "Wi-Fi" else "BLE").distinct().joinToString(" · "),
                        fontSize = 11.sp, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (m.threat) Text("CHECK", fontSize = 10.sp, color = DangerRed, letterSpacing = 1.sp, modifier = Modifier.padding(end = 6.dp))
                TextButton(onClick = onClose) { Text("✕", fontSize = 14.sp) }
            }
            val now = System.currentTimeMillis()
            Text(
                listOfNotNull(
                    "${d.lastRssi} dBm",
                    "last heard ${agoText(now, d.lastSeenMs)}",
                    "first ${agoText(now, d.firstSeenMs)}",
                    d.seenCount.takeIf { it > 0 }?.let { "heard $it×" },
                ).joinToString(" · "),
                fontSize = 11.sp, color = Color(0xFFCBD5E1), modifier = Modifier.padding(top = 6.dp),
            )
            Text("${m.motionLabel} — ${m.locationBasis}", fontSize = 10.sp, color = ConsoleMuted)
            if (trail.isNotEmpty()) {
                val (spread, strongest) = remember(trail) {
                    // Farthest-from-first is enough for "how spread out" and stays O(n).
                    val first = trail.first()
                    trail.maxOf { com.rfwatch.core.geo.haversineM(first.lat, first.lon, it.lat, it.lon) } to trail.maxBy { it.rssi }
                }
                Text(
                    "Trail: ${plural(trail.sumOf { it.count }, "hearing")} at ${plural(trail.size, "place")}, spread ${spread.toInt()} m · strongest ${strongest.rssi} dBm " +
                        "${agoText(now, strongest.timestampMs)}",
                    fontSize = 10.sp, color = RadarCyan,
                )
            }
            com.rfwatch.app.ui.DeviceLinks(vm, d.id, here = com.rfwatch.app.ui.DeviceDest.MAP, modifier = Modifier.padding(top = 6.dp))
        }
    }
}

private fun plural(n: Int, word: String) = "$n $word" + if (n == 1) "" else "s"

/** Several devices heard from one spot: pick one. */
@Composable
private fun MapStackCard(group: List<MappedDevice>, modifier: Modifier, onClose: () -> Unit, onPick: (String) -> Unit) {
    Card(modifier, colors = CardDefaults.cardColors(containerColor = ConsolePanel.copy(alpha = 0.97f)), border = BorderStroke(1.dp, ConsoleOutline)) {
        Column(Modifier.padding(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${group.size} devices heard from this spot", fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                    val threats = group.count { it.threat }
                    Text(
                        (if (threats > 0) "$threats worth checking · " else "") + "same phone GPS point — tap one",
                        fontSize = 10.sp, color = if (threats > 0) DangerRed else ConsoleMuted,
                    )
                }
                TextButton(onClick = onClose) { Text("✕", fontSize = 14.sp) }
            }
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 230.dp)) {
                items(group.sortedWith(compareByDescending<MappedDevice> { stackPriority(it) }.thenByDescending { it.entity.lastSeenMs }), key = { it.entity.id }) { m ->
                    val color = deviceCategoryColor(m.entity.category, m.entity.classification)
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).clickable { onPick(m.entity.id) }.padding(vertical = 6.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(kindIcon(m.kind), null, tint = color, modifier = Modifier.size(18.dp))
                        Column(Modifier.weight(1f).padding(start = 8.dp)) {
                            Text(m.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, color = if (m.threat) DangerRed else Color(0xFFE2E8F0))
                            Text(listOfNotNull(m.entity.brand, m.kind.label).distinct().joinToString(" · "), fontSize = 10.sp, color = ConsoleMuted, maxLines = 1)
                        }
                        Text("${m.entity.lastRssi} dBm", fontSize = 11.sp, color = ConsoleMuted)
                    }
                }
            }
        }
    }
}

@Composable
private fun MapOverlayButton(label: String, enabled: Boolean, active: Boolean = false, onClick: () -> Unit) {
    Surface(
        onClick = onClick, enabled = enabled,
        shape = RoundedCornerShape(8.dp),
        color = if (active) RadarCyan.copy(alpha = 0.25f) else ConsolePanel.copy(alpha = 0.92f),
        border = BorderStroke(1.dp, if (active) RadarCyan else ConsoleOutline),
    ) {
        Text(label, Modifier.padding(horizontal = 10.dp, vertical = 6.dp), fontSize = 12.sp,
            color = if (enabled) Color(0xFFE2E8F0) else ConsoleMuted)
    }
}

private fun fitMapToDevices(map: MapLibreMap?, devices: List<MappedDevice>, userLocation: GpsFix?) {
    map ?: return
    val points = devices.map { LatLng(it.latitude, it.longitude) } +
        listOfNotNull(userLocation?.let { LatLng(it.lat, it.lon) })
    if (points.isEmpty()) return
    // Drop any card padding left by a device focus so "fit" uses the whole map.
    map.moveCamera(CameraUpdateFactory.newCameraPosition(
        org.maplibre.android.camera.CameraPosition.Builder(map.cameraPosition).padding(0.0, 0.0, 0.0, 0.0).build(),
    ))
    if (points.size == 1) {
        map.animateCamera(CameraUpdateFactory.newLatLngZoom(points.first(), 15.0), 350)
    } else {
        val bounds = LatLngBounds.Builder().apply { points.forEach(::include) }.build()
        map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, 48), 350)
    }
}

@Composable
private fun OfflineDeviceMap(devices: List<DeviceEntity>, events: List<com.rfwatch.app.data.EventEntity>, modifier: Modifier) {
    var selectedEvent by remember { mutableStateOf<com.rfwatch.app.data.EventEntity?>(null) }
    val mapped = devices.filter { it.observedLat != null && it.observedLon != null }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("DEVICE MAP", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
        Text("Offline location view", fontSize = 18.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold)
        Text("Street basemap is disabled in the offline build. Build the mapsOnline variant for the interactive OpenStreetMap layer.", fontSize = 12.sp, color = Color(0xFF9CA3AF))
        if (mapped.isEmpty()) {
            Text("No GPS-tagged detections yet. Enable phone Location while scanning.", Modifier.padding(top = 10.dp), fontSize = 13.sp)
            return@Column
        }
        Text("${mapped.size} detections · dots show where the phone heard the radio, not transmitter positions", Modifier.padding(top = 8.dp), fontSize = 11.sp)
        Canvas(Modifier.fillMaxWidth().weight(0.42f).padding(vertical = 8.dp)) {
            val minLat = mapped.minOf { it.observedLat!! }
            val maxLat = mapped.maxOf { it.observedLat!! }
            val minLon = mapped.minOf { it.observedLon!! }
            val maxLon = mapped.maxOf { it.observedLon!! }
            val pad = 20f
            val spanX = (maxLon - minLon).coerceAtLeast(0.00001)
            val spanY = (maxLat - minLat).coerceAtLeast(0.00001)
            mapped.forEach { item ->
                val x = pad + ((item.observedLon!! - minLon) / spanX * (size.width - 2 * pad)).toFloat()
                val y = size.height - pad - ((item.observedLat!! - minLat) / spanY * (size.height - 2 * pad)).toFloat()
                drawCircle(deviceCategoryColor(item.category, item.classification), 6f, androidx.compose.ui.geometry.Offset(x, y))
            }
        }
        LazyColumn(Modifier.weight(0.58f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (events.any { it.isMappableHistory() }) item(key = "history") { HistoryPanel(events, selectedEvent) { selectedEvent = it } }
            items(mapped, key = { it.id }) { item ->
                Text(
                    "${item.displayName ?: item.id} · ${item.band} ${item.lastRssi} dBm · ${String.format(Locale.US, "%.5f, %.5f", item.observedLat, item.observedLon)}",
                    fontSize = 10.sp, color = deviceCategoryColor(item.category, item.classification),
                )
            }
        }
    }
}


private fun colorHex(color: Color): String = "#%06X".format(Locale.US, color.toArgb() and 0xFFFFFF)

private const val OSM_RASTER_STYLE = """
{
  "version": 8,
  "glyphs": "asset://glyphs/{fontstack}/{range}.pbf",
  "sources": {
    "openstreetmap": {
      "type": "raster",
      "tiles": ["https://tile.openstreetmap.org/{z}/{x}/{y}.png"],
      "tileSize": 256,
      "maxzoom": 19,
      "attribution": "© OpenStreetMap contributors"
    }
  },
  "layers": [{"id":"osm-raster","type":"raster","source":"openstreetmap"}]
}
"""
