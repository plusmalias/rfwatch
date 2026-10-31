package com.rfwatch.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.FilterChip
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.rfwatch.app.BuildConfig
import com.rfwatch.app.data.DeviceEntity
import com.rfwatch.app.ui.RfWatchViewModel
import com.rfwatch.app.ui.theme.ConsoleOutline
import com.rfwatch.app.ui.theme.ConsolePanel
import com.rfwatch.app.ui.theme.ConsolePanelRaised
import com.rfwatch.app.ui.theme.ConsoleMuted
import com.rfwatch.core.df.DriveByEstimator
import com.rfwatch.core.df.EmitterEstimate
import com.rfwatch.core.df.EstimateStatus
import com.rfwatch.core.model.GpsFix
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.conflate
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapLibreMapOptions
import org.maplibre.android.maps.MapView
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.textAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.textColor
import org.maplibre.android.style.layers.PropertyFactory.textFont
import org.maplibre.android.style.layers.PropertyFactory.textIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.textField
import org.maplibre.android.style.layers.PropertyFactory.textHaloColor
import org.maplibre.android.style.layers.PropertyFactory.textHaloWidth
import org.maplibre.android.style.layers.PropertyFactory.textOffset
import org.maplibre.android.style.layers.PropertyFactory.textSize
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonOptions
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.maps.Style
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.Point
import java.util.Locale

private const val RADIO_SOURCE = "rfwatch-radio-source"
private const val USER_SOURCE = "rfwatch-user-source"
private const val RADIO_LABEL_SOURCE = "rfwatch-radio-label-source"
private const val USER_LABEL_SOURCE = "rfwatch-user-label-source"
private const val RADIO_LAYER = "rfwatch-radio-points"
private const val CLUSTER_LAYER = "rfwatch-radio-clusters"
private const val CLUSTER_COUNT_LAYER = "rfwatch-cluster-count"
private const val USER_LAYER = "rfwatch-user-point"
private const val USER_LABEL_LAYER = "rfwatch-user-label"
private const val HISTORY_SOURCE = "rfwatch-history-source"
private const val HISTORY_LAYER = "rfwatch-history-points"
private const val RADIO_UPDATE_INTERVAL_MS = 1_000L

// Bundled with the mapsOnline flavor (assets/glyphs, ASCII range only) so labels need no font host.
private val MAP_LABEL_FONT = arrayOf("Noto Sans Bold")

private data class MappedDevice(
    val entity: DeviceEntity,
    val latitude: Double,
    val longitude: Double,
    val estimate: EmitterEstimate?,
) {
    val motionLabel: String get() = when (estimate?.status) {
        EstimateStatus.MOBILE -> "MOVEMENT CANDIDATE"
        EstimateStatus.LOCATED -> "FIXED-SOURCE ESTIMATE"
        else -> "MOTION UNKNOWN"
    }
    val locationBasis: String get() = when (estimate?.status) {
        EstimateStatus.LOCATED -> "Drive-by fit · ±${estimate.ellipse?.semiMajorM?.toInt() ?: "?"} m error"
        EstimateStatus.MOBILE -> "Last phone GPS point where signal was heard; device position unknown"
        else -> "Last phone GPS point where signal was heard · " +
            (estimate?.reason?.let { "not located: $it" } ?: "not located yet (needs a live, repeatedly heard device)")
    }
    val markerColor: String get() = colorHex(deviceCategoryColor(entity.category, entity.classification))
    val motionColor: String get() = when (estimate?.status) {
        EstimateStatus.MOBILE -> "#F59E0B"
        EstimateStatus.LOCATED -> "#34D399"
        else -> "#CBD5E1"
    }
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
    var mapError by remember { mutableStateOf(false) }
    val events by vm.events.collectAsState()
    var showHistory by remember { mutableStateOf(false) }
    var showMapHelp by remember { mutableStateOf(false) }
    var selectedEvent by remember { mutableStateOf<com.rfwatch.app.data.EventEntity?>(null) }
    val latestEvents by rememberUpdatedState(events)

    val mapped = remember(devices, contacts) {
        devices.mapNotNull { device ->
            val estimate = contacts[device.id]?.estimate
            val lat = if (estimate?.status == EstimateStatus.LOCATED) estimate.lat else device.observedLat
            val lon = if (estimate?.status == EstimateStatus.LOCATED) estimate.lon else device.observedLon
            if (lat == null || lon == null) null else MappedDevice(device, lat, lon, estimate)
        }
    }
    val movingCount = mapped.count { it.estimate?.status == EstimateStatus.MOBILE }
    val locatedCount = mapped.count { it.estimate?.status == EstimateStatus.LOCATED }
    val selected = mapped.firstOrNull { it.entity.id == selectedId }
    val useDeviceFilter by vm.mapUsesDeviceFilter.collectAsState()
    val deviceFilter by vm.deviceFilter.collectAsState()
    var navNotice by remember { mutableStateOf<String?>(null) }
    val nav by vm.deviceNav.collectAsState()
    LaunchedEffect(nav, styleReady.value) {
        val n = nav ?: return@LaunchedEffect
        if (n.dest != com.rfwatch.app.ui.DeviceDest.MAP || !styleReady.value) return@LaunchedEffect
        vm.consumeNav(n)
        // A device hidden by the shared filter is still shown: drop the filter rather than fail.
        if (mapped.none { it.entity.id == n.deviceId } && useDeviceFilter) vm.mapUsesDeviceFilter.value = false
        val target = vm.devices.value.firstOrNull { it.id == n.deviceId }
        val est = contacts[n.deviceId]?.estimate
        val lat = if (est?.status == EstimateStatus.LOCATED) est.lat else target?.observedLat
        val lon = if (est?.status == EstimateStatus.LOCATED) est.lon else target?.observedLon
        if (lat != null && lon != null) {
            selectedId = n.deviceId
            navNotice = null
            mapRef.value?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(lat, lon), 17.0), 350)
        } else {
            navNotice = "${target?.displayName ?: n.deviceId}: no GPS-tagged sighting yet — keep Location on while scanning."
        }
    }

    // Direction-finding fits update while this screen is visible. Only a bounded set of
    // recent GPS histories are evaluated; under-sampled emitters remain explicitly unknown.
    LaunchedEffect(vm) { if (BuildConfig.DEBUG) vm.logDuplicateReport() }
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
            map.setStyle(Style.Builder().fromJson(OSM_RASTER_STYLE)) { style ->
                val empty = FeatureCollection.fromFeatures(emptyArray<Feature>())
                val clusterOptions = { GeoJsonOptions().withCluster(true).withClusterRadius(42).withClusterMaxZoom(15) }
                // Text labels live in their own sources. A symbol layer makes MapLibre wait for
                // glyphs before it finishes a source's tiles; sharing a source with the circle
                // layers meant a missing/slow glyph range hid every marker. Separate label
                // sources keep the markers independent of font loading.
                style.addSource(GeoJsonSource(RADIO_SOURCE, empty, clusterOptions()))
                style.addSource(GeoJsonSource(RADIO_LABEL_SOURCE, empty, clusterOptions()))
                style.addSource(GeoJsonSource(USER_SOURCE, empty))
                style.addSource(GeoJsonSource(USER_LABEL_SOURCE, empty))

                val clustered = Expression.has("point_count")
                style.addLayer(
                    CircleLayer(CLUSTER_LAYER, RADIO_SOURCE).withFilter(clustered).withProperties(
                        circleColor("#38BDF8"),
                        circleRadius(
                            Expression.step(
                                Expression.get("point_count"), Expression.literal(15),
                                Expression.stop(10, Expression.literal(20)),
                                Expression.stop(50, Expression.literal(26)),
                            ),
                        ),
                        circleStrokeColor("#F8FAFC"), circleStrokeWidth(1.5f),
                    ),
                )
                style.addLayer(
                    CircleLayer(RADIO_LAYER, RADIO_SOURCE).withFilter(Expression.not(clustered)).withProperties(
                        circleColor(Expression.toColor(Expression.get("markerColor"))),
                        circleRadius(7f),
                        circleStrokeColor(Expression.toColor(Expression.get("motionColor"))),
                        circleStrokeWidth(2.5f),
                    ),
                )
                style.addLayer(
                    CircleLayer(USER_LAYER, USER_SOURCE).withProperties(
                        circleColor("#38BDF8"), circleRadius(9f),
                        circleStrokeColor("#F8FAFC"), circleStrokeWidth(3f),
                    ),
                )
                style.addLayer(
                    SymbolLayer(CLUSTER_COUNT_LAYER, RADIO_LABEL_SOURCE).withFilter(clustered).withProperties(
                        textField("{point_count_abbreviated}"), textFont(MAP_LABEL_FONT), textSize(11f),
                        textColor("#08212A"), textHaloColor("#E0F7FA"), textHaloWidth(0.5f),
                        textAllowOverlap(true), textIgnorePlacement(true),
                    ),
                )
                style.addLayer(
                    SymbolLayer(USER_LABEL_LAYER, USER_LABEL_SOURCE).withProperties(
                        textField("YOU"), textFont(MAP_LABEL_FONT), textSize(10f), textColor("#08212A"),
                        textHaloColor("#F8FAFC"), textHaloWidth(1f), textOffset(arrayOf(0f, 1.8f)),
                        textAllowOverlap(true), textIgnorePlacement(true),
                    ),
                )
                style.addSource(GeoJsonSource(HISTORY_SOURCE, empty))
                style.addLayer(
                    CircleLayer(HISTORY_LAYER, HISTORY_SOURCE).withProperties(
                        circleColor(Expression.toColor(Expression.get("color"))), circleRadius(9f),
                        circleStrokeColor("#FFFFFF"), circleStrokeWidth(2f),
                    ),
                )
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
                } else if (hit.hasNonNullValueForProperty("point_count")) {
                    val source = map.style?.getSource(RADIO_SOURCE) as? GeoJsonSource
                    val zoom = source?.getClusterExpansionZoom(hit)?.toDouble() ?: map.cameraPosition.zoom + 1.0
                    val point = hit.geometry() as? Point
                    if (point != null) map.easeCamera(
                        CameraUpdateFactory.newLatLngZoom(LatLng(point.latitude(), point.longitude()), zoom), 250,
                    )
                    selectedId = null
                } else {
                    selectedId = hit.getStringProperty("deviceId")
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
            val features = current.map { item ->
                Feature.fromGeometry(Point.fromLngLat(item.longitude, item.latitude)).apply {
                    addStringProperty("deviceId", item.entity.id)
                    addStringProperty("label", item.entity.displayName ?: item.entity.id)
                    addStringProperty("band", item.entity.band)
                    addNumberProperty("rssi", item.entity.lastRssi)
                    addStringProperty("category", item.entity.category ?: item.entity.band)
                    addStringProperty("motion", item.motionLabel)
                    addStringProperty("basis", item.locationBasis)
                    addStringProperty("markerColor", item.markerColor)
                    addStringProperty("motionColor", item.motionColor)
                }
            }
            val data = FeatureCollection.fromFeatures(features)
            mapRef.value?.getStyle { style ->
                (style.getSource(RADIO_SOURCE) as? GeoJsonSource)?.setGeoJson(data)
                (style.getSource(RADIO_LABEL_SOURCE) as? GeoJsonSource)?.setGeoJson(data)
            }
            delay(RADIO_UPDATE_INTERVAL_MS)
        }
    }

    LaunchedEffect(userLocation, styleReady.value) {
        if (!styleReady.value) return@LaunchedEffect
        val userFeature = userLocation?.let { fix ->
            Feature.fromGeometry(Point.fromLngLat(fix.lon, fix.lat)).apply {
                addStringProperty("kind", "user")
            }
        }
        val collection = userFeature?.let { FeatureCollection.fromFeature(it) }
            ?: FeatureCollection.fromFeatures(emptyArray<Feature>())
        mapRef.value?.getStyle { style ->
            (style.getSource(USER_SOURCE) as? GeoJsonSource)?.setGeoJson(collection)
            (style.getSource(USER_LABEL_SOURCE) as? GeoJsonSource)?.setGeoJson(collection)
        }
    }

    LaunchedEffect(styleReady.value, userLocation, mapped) {
        if (!styleReady.value || cameraPositioned.value) return@LaunchedEffect
        val location = userLocation?.let { LatLng(it.lat, it.lon) }
            ?: mapped.firstOrNull()?.let { LatLng(it.latitude, it.longitude) }
        if (location != null) {
            mapRef.value?.animateCamera(CameraUpdateFactory.newLatLngZoom(location, if (userLocation != null) 15.5 else 13.0), 350)
            cameraPositioned.value = true
        }
    }

    // History layer: plot logged surveillance events; hide device markers while it is shown.
    LaunchedEffect(styleReady.value, showHistory, events) {
        if (!styleReady.value) return@LaunchedEffect
        val features = if (!showHistory) emptyList() else events.filter { it.isMappableHistory() }.map { e ->
            Feature.fromGeometry(Point.fromLngLat(e.lon!!, e.lat!!)).apply {
                addNumberProperty("rowId", e.rowId)
                addStringProperty("color", HISTORY_KINDS[e.kind]?.first ?: "#94A3B8")
            }
        }
        mapRef.value?.getStyle { style ->
            (style.getSource(HISTORY_SOURCE) as? GeoJsonSource)?.setGeoJson(FeatureCollection.fromFeatures(features))
            val vis = org.maplibre.android.style.layers.PropertyFactory.visibility(
                if (showHistory) org.maplibre.android.style.layers.Property.NONE else org.maplibre.android.style.layers.Property.VISIBLE,
            )
            listOf(RADIO_LAYER, CLUSTER_LAYER, CLUSTER_COUNT_LAYER).forEach { style.getLayer(it)?.setProperties(vis) }
        }
    }

    Column(
        modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)
            .then(if (showHistory) Modifier.verticalScroll(rememberScrollState()) else Modifier),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            FilterChip(selected = !showHistory, onClick = { showHistory = false }, label = { Text("Devices") })
            FilterChip(selected = showHistory, onClick = { showHistory = true; selectedId = null }, label = { Text("History ${events.count { it.isMappableHistory() }}") })
            if (!showHistory) FilterChip(
                selected = useDeviceFilter,
                onClick = { vm.mapUsesDeviceFilter.value = !useDeviceFilter },
                label = { Text(if (useDeviceFilter && !deviceFilter.isDefault) "Devices filters ✓" else "Devices filters") },
            )
        }
        navNotice?.let { Text(it, fontSize = 11.sp, color = com.rfwatch.app.ui.theme.WarnAmber) }
        Row(Modifier.fillMaxWidth().padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(if (showHistory) "ENCOUNTER HISTORY" else "LIVE DEVICE MAP", fontSize = 10.sp, color = MaterialTheme.colorScheme.primary, letterSpacing = 1.2.sp)
                Text(
                    "${mapped.size} on map · $locatedCount located · $movingCount moving",
                    fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold,
                )
            }
            androidx.compose.material3.TextButton(onClick = { showMapHelp = !showMapHelp }) { Text(if (showMapHelp) "Hide help" else "ⓘ Help", fontSize = 12.sp) }
        }
        // Compact legend; the full explanation is behind Help.
        Row(Modifier.fillMaxWidth().padding(bottom = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf(Color(0xFF34D399) to "position estimated", Color(0xFFF59E0B) to "moving", Color(0xFFCBD5E1) to "where you heard it").forEach { (c, label) ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    Canvas(Modifier.size(10.dp)) { drawCircle(c, style = androidx.compose.ui.graphics.drawscope.Stroke(2.5f)) }
                    Text(label, fontSize = 10.sp, color = Color(0xFF9CA3AF))
                }
            }
        }
        if (showMapHelp) Text(
            "Fill colour = device category. Outline: green = position estimated · amber = moving · grey = drawn where your phone heard it (not the device). " +
                "Estimates need ≥${DriveByEstimator.MIN_SAMPLES} GPS-tagged hearings spread over ≥${DriveByEstimator.MIN_SPREAD_M.toInt()} m — walk or drive past; standing still keeps everything grey. " +
                "Blue circles are groups — tap to zoom in. Online tiles reveal the visible area to the tile server; radio records stay on-device.",
            fontSize = 10.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(bottom = 5.dp),
        )
        Box(if (showHistory) Modifier.fillMaxWidth().height(380.dp) else Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            Column(Modifier.align(Alignment.TopEnd).padding(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.End) {
                MapOverlayButton("◎ Me", enabled = userLocation != null) {
                    userLocation?.let { fix -> mapRef.value?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lon), 16.0), 350) }
                }
                MapOverlayButton("⤢ Fit", enabled = mapped.isNotEmpty() || userLocation != null) { fitMapToDevices(mapRef.value, mapped, userLocation) }
            }
            if (mapError) {
                Card(Modifier.align(Alignment.Center).padding(20.dp), colors = CardDefaults.cardColors(containerColor = ConsolePanel), border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleOutline)) {
                    Text("Map tiles unavailable. Check internet and retry by reopening Map.", Modifier.padding(12.dp), fontSize = 12.sp)
                }
            } else if (mapped.isEmpty()) {
                Card(Modifier.align(Alignment.TopCenter).padding(8.dp), colors = CardDefaults.cardColors(containerColor = ConsolePanel.copy(alpha = 0.94f)), border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleOutline)) {
                    Text("Waiting for GPS-tagged detections", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 11.sp)
                }
            }
            Text(
                "© OpenStreetMap contributors",
                Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 7.dp),
                fontSize = 10.sp, color = Color(0xFF17333D),
            )
        }
        if (showHistory) {
            HistoryPanel(events, selectedEvent) { e ->
                selectedEvent = e
                if (e.lat != null && e.lon != null) mapRef.value?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(e.lat!!, e.lon!!), 16.0), 350)
            }
        }
        if (!showHistory) selected?.let { item ->
            Card(Modifier.fillMaxWidth().padding(top = 5.dp), colors = CardDefaults.cardColors(containerColor = ConsolePanel), border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleOutline)) {
                Column(Modifier.padding(9.dp)) {
                    Text(item.entity.displayName ?: item.entity.id, fontSize = 13.sp)
                    Text("${item.motionLabel} · ${item.locationBasis}", fontSize = 10.sp, color = Color(0xFF9CA3AF))
                    Text(
                        "${item.entity.band} · ${item.entity.lastRssi} dBm · ${String.format(Locale.US, "%.5f, %.5f", item.latitude, item.longitude)}",
                        fontSize = 10.sp, color = deviceCategoryColor(item.entity.category, item.entity.classification),
                    )
                    com.rfwatch.app.ui.DeviceLinks(vm, item.entity.id, here = com.rfwatch.app.ui.DeviceDest.MAP, modifier = Modifier.padding(top = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun MapOverlayButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    androidx.compose.material3.Surface(
        onClick = onClick, enabled = enabled,
        shape = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
        color = ConsolePanel.copy(alpha = 0.92f),
        border = androidx.compose.foundation.BorderStroke(1.dp, ConsoleOutline),
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
