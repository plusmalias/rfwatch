package com.rfwatch.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.runtime.setValue
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
import com.rfwatch.core.df.EmitterEstimate
import com.rfwatch.core.df.EstimateStatus
import com.rfwatch.core.model.GpsFix
import kotlinx.coroutines.delay
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
import org.maplibre.android.style.layers.PropertyFactory.textColor
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
private const val RADIO_LAYER = "rfwatch-radio-points"
private const val CLUSTER_LAYER = "rfwatch-radio-clusters"
private const val CLUSTER_COUNT_LAYER = "rfwatch-cluster-count"
private const val USER_LAYER = "rfwatch-user-point"
private const val USER_LABEL_LAYER = "rfwatch-user-label"

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
        else -> "Last phone GPS point where signal was heard; device position unknown"
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
    val devices by vm.devices.collectAsState()
    val contacts by vm.contacts.collectAsState()
    val userLocation by vm.userLocation.collectAsState()
    if (BuildConfig.FLAVOR == "mapsOnline") {
        OnlineDeviceMap(devices, contacts, userLocation, vm, modifier)
    } else {
        OfflineDeviceMap(devices, modifier)
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

    val mapped = remember(devices, contacts) {
        devices.mapNotNull { device ->
            val estimate = contacts[device.id]?.estimate
            val lat = if (estimate?.status == EstimateStatus.LOCATED) estimate.lat else device.observedLat
            val lon = if (estimate?.status == EstimateStatus.LOCATED) estimate.lon else device.observedLon
            if (lat == null || lon == null) null else MappedDevice(device, lat, lon, estimate)
        }
    }
    val movingCount = mapped.count { it.estimate?.status == EstimateStatus.MOBILE }
    val selected = mapped.firstOrNull { it.entity.id == selectedId }

    // Direction-finding fits update while this screen is visible. Only a bounded set of
    // recent GPS histories are evaluated; under-sampled emitters remain explicitly unknown.
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
                android.util.Log.i("RFWatchMap", "OSM style loaded")
                val empty = FeatureCollection.fromFeatures(emptyArray<Feature>())
                val radios = GeoJsonSource(
                    RADIO_SOURCE,
                    empty,
                    GeoJsonOptions().withCluster(true).withClusterRadius(42).withClusterMaxZoom(15),
                )
                style.addSource(radios)
                style.addSource(GeoJsonSource(USER_SOURCE, empty))

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
                    SymbolLayer(CLUSTER_COUNT_LAYER, RADIO_SOURCE).withFilter(clustered).withProperties(
                        textField("{point_count_abbreviated}"), textSize(11f), textColor("#08212A"),
                        textHaloColor("#E0F7FA"), textHaloWidth(0.5f),
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
                    SymbolLayer(USER_LABEL_LAYER, USER_SOURCE).withProperties(
                        textField("YOU"), textSize(10f), textColor("#08212A"),
                        textHaloColor("#F8FAFC"), textHaloWidth(1f), textOffset(arrayOf(0f, 1.8f)),
                    ),
                )
                styleReady.value = true
                mapError = false
            }
            map.addOnMapClickListener { latLng ->
                val pixel = map.projection.toScreenLocation(latLng)
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

    LaunchedEffect(mapped, styleReady.value) {
        android.util.Log.i("RFWatchMap", "radio effect ready=${styleReady.value} count=${mapped.size}")
        if (!styleReady.value) return@LaunchedEffect
        delay(600L) // coalesce scan DB emissions on older phones.
        val features = mapped.map { item ->
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
        android.util.Log.i("RFWatchMap", "built radio features=${features.size}")
        val data = FeatureCollection.fromFeatures(features)
        mapRef.value?.getStyle { style ->
            val source = style.getSource(RADIO_SOURCE) as? GeoJsonSource
            android.util.Log.i("RFWatchMap", "radio features=${features.size} source=${source != null}")
            source?.setGeoJson(data)
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
            val source = style.getSource(USER_SOURCE) as? GeoJsonSource
            android.util.Log.i("RFWatchMap", "user feature=${userFeature != null} source=${source != null}")
            source?.setGeoJson(collection)
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

    Column(modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 8.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
            Column(Modifier.weight(1f)) {
                Text("LIVE DEVICE MAP", fontSize = 17.sp)
                Text("${mapped.size} mapped · $movingCount movement candidates", fontSize = 11.sp, color = Color(0xFF9CA3AF))
            }
            Column(horizontalAlignment = Alignment.End) {
                OutlinedButton(onClick = {
                    val fix = userLocation
                    if (fix != null) {
                        mapRef.value?.animateCamera(CameraUpdateFactory.newLatLngZoom(LatLng(fix.lat, fix.lon), 16.0), 350)
                    }
                }, enabled = userLocation != null) { Text("My location") }
                OutlinedButton(onClick = { fitMapToDevices(mapRef.value, mapped, userLocation) }) { Text("Fit all") }
            }
        }
        Text(
            "Green outline = fixed-source estimate · amber = motion candidate · grey = only heard here",
            fontSize = 10.sp, color = Color(0xFF9CA3AF), modifier = Modifier.padding(bottom = 5.dp),
        )
        Box(Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(factory = { mapView }, modifier = Modifier.fillMaxSize())
            if (mapError) {
                Card(Modifier.align(Alignment.Center).padding(20.dp)) {
                    Text("Map tiles unavailable. Check internet and retry by reopening Map.", Modifier.padding(12.dp), fontSize = 12.sp)
                }
            } else if (mapped.isEmpty()) {
                Card(Modifier.align(Alignment.TopCenter).padding(8.dp)) {
                    Text("Waiting for GPS-tagged detections", Modifier.padding(horizontal = 10.dp, vertical = 7.dp), fontSize = 11.sp)
                }
            }
            Text(
                "© OpenStreetMap contributors",
                Modifier.align(Alignment.BottomStart).padding(start = 8.dp, bottom = 7.dp),
                fontSize = 10.sp, color = Color(0xFF17333D),
            )
        }
        Text("Online map tiles reveal the visible area requested; radio records stay on-device.", Modifier.padding(top = 4.dp), fontSize = 9.sp, color = Color(0xFF9CA3AF))
        selected?.let { item ->
            Card(Modifier.fillMaxWidth().padding(top = 5.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)) {
                Column(Modifier.padding(9.dp)) {
                    Text(item.entity.displayName ?: item.entity.id, fontSize = 13.sp)
                    Text("${item.motionLabel} · ${item.locationBasis}", fontSize = 10.sp, color = Color(0xFF9CA3AF))
                    Text(
                        "${item.entity.band} · ${item.entity.lastRssi} dBm · ${String.format(Locale.US, "%.5f, %.5f", item.latitude, item.longitude)}",
                        fontSize = 10.sp, color = deviceCategoryColor(item.entity.category, item.entity.classification),
                    )
                }
            }
        }
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
private fun OfflineDeviceMap(devices: List<DeviceEntity>, modifier: Modifier) {
    val mapped = devices.filter { it.observedLat != null && it.observedLon != null }
    Column(modifier.fillMaxSize().padding(12.dp)) {
        Text("Device map", fontSize = 18.sp)
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
