package com.rfwatch.app.sensors

import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import com.rfwatch.core.df.HeadingFusion
import com.rfwatch.core.model.GpsFix
import com.rfwatch.core.model.Heading
import com.rfwatch.core.model.SensorAccuracy
import kotlin.math.sqrt
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Produces a fused true-north [Heading] stream (step 1). The device azimuth comes from
 * TYPE_ROTATION_VECTOR, is corrected to true north with the local magnetic declination
 * from [GeomagneticField] (using the last GPS fix), and is handed — together with the
 * magnetometer field magnitude and reported accuracy — to the Android-free
 * [HeadingFusion] policy, which decides whether to trust the compass or fall back to GPS
 * course (e.g. inside a moving vehicle).
 *
 * The caller pushes the latest [GpsFix] via [updateFix]; the flow re-emits on every
 * rotation-vector sample.
 */
class HeadingProvider(context: Context) {

    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val magnetometer: Sensor? = sensorManager.getDefaultSensor(Sensor.TYPE_MAGNETIC_FIELD)

    @Volatile private var latestFix: GpsFix? = null
    @Volatile private var fieldMagnitudeUt: Double? = null
    @Volatile private var magAccuracy: SensorAccuracy = SensorAccuracy.UNRELIABLE

    fun updateFix(fix: GpsFix?) { latestFix = fix }

    fun headings(): Flow<Heading> = callbackFlow {
        val rotMatrix = FloatArray(9)
        val orientation = FloatArray(3)

        val listener = object : SensorEventListener {
            override fun onSensorChanged(event: SensorEvent) {
                when (event.sensor.type) {
                    Sensor.TYPE_MAGNETIC_FIELD -> {
                        val (x, y, z) = Triple(event.values[0], event.values[1], event.values[2])
                        fieldMagnitudeUt = sqrt((x * x + y * y + z * z).toDouble())
                    }
                    Sensor.TYPE_ROTATION_VECTOR -> {
                        SensorManager.getRotationMatrixFromVector(rotMatrix, event.values)
                        SensorManager.getOrientation(rotMatrix, orientation)
                        val magneticDeg = Math.toDegrees(orientation[0].toDouble())
                        val fix = latestFix
                        val declination = fix?.let {
                            GeomagneticField(
                                it.lat.toFloat(), it.lon.toFloat(), 0f, System.currentTimeMillis(),
                            ).declination.toDouble()
                        } ?: 0.0
                        val heading = HeadingFusion.fuse(
                            magneticHeadingDeg = magneticDeg,
                            declinationDeg = declination,
                            accuracy = magAccuracy,
                            fieldMagnitudeUT = fieldMagnitudeUt,
                            gps = fix,
                        )
                        trySend(heading)
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
                if (sensor.type == Sensor.TYPE_MAGNETIC_FIELD) {
                    magAccuracy = mapAccuracy(accuracy)
                }
            }
        }

        rotationSensor?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        magnetometer?.let { sensorManager.registerListener(listener, it, SensorManager.SENSOR_DELAY_UI) }
        if (rotationSensor == null) {
            trySend(Heading(null, com.rfwatch.core.model.HeadingSource.NONE, SensorAccuracy.NO_CONTACT))
        }
        awaitClose { sensorManager.unregisterListener(listener) }
    }

    private fun mapAccuracy(a: Int): SensorAccuracy = when (a) {
        SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> SensorAccuracy.HIGH
        SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> SensorAccuracy.MEDIUM
        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> SensorAccuracy.LOW
        SensorManager.SENSOR_STATUS_NO_CONTACT -> SensorAccuracy.NO_CONTACT
        else -> SensorAccuracy.UNRELIABLE
    }
}
