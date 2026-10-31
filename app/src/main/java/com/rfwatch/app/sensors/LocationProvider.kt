package com.rfwatch.app.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import com.rfwatch.core.model.GpsFix
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * GPS fixes via the platform [LocationManager] — deliberately not Google Play Services, to
 * keep the default build free of Google dependencies and network calls. Emits [GpsFix]
 * with bearing/speed when the provider supplies them (needed by heading fusion and drive-by
 * localisation).
 */
class LocationProvider(context: Context) {

    private val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    @SuppressLint("MissingPermission") // caller gates on ACCESS_FINE_LOCATION
    fun fixes(minIntervalMs: Long = 1000L, minDistanceM: Float = 0f): Flow<GpsFix> = callbackFlow {
        val listener = LocationListener { loc -> trySend(loc.toFix()) }
        val providers = buildList {
            if (lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) add(LocationManager.GPS_PROVIDER)
            if (lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) add(LocationManager.NETWORK_PROVIDER)
        }
        // Explicit main looper: the flow is collected on a background ingest thread, which has none.
        providers.forEach { lm.requestLocationUpdates(it, minIntervalMs, minDistanceM, listener, Looper.getMainLooper()) }
        awaitClose { lm.removeUpdates(listener) }
    }

    private fun Location.toFix() = GpsFix(
        lat = latitude,
        lon = longitude,
        accuracyM = if (hasAccuracy()) accuracy.toDouble() else 50.0,
        timestampMs = time,
        bearingDeg = if (hasBearing()) bearing.toDouble() else null,
        speedMps = if (hasSpeed()) speed.toDouble() else null,
    )
}
