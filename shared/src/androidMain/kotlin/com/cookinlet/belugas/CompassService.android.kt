package com.cookinlet.belugas

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val SAMPLE_COUNT = 8
private const val TIMEOUT_MS = 1200L

actual class CompassService actual constructor() {
    actual suspend fun getCurrentHeading(): HeadingEstimate? {
        val sensorManager = androidContext.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
            ?: return null
        val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) ?: return null

        return withTimeoutOrNull(TIMEOUT_MS) {
            suspendCancellableCoroutine { continuation ->
                var sinSum = 0.0
                var cosSum = 0.0
                var sampleCount = 0
                // Android only calls onAccuracyChanged when accuracy actually changes, so it may
                // never fire in this short window — default to HIGH (optimistic) in that case.
                var worstAccuracy = SensorManager.SENSOR_STATUS_ACCURACY_HIGH
                val rotationMatrix = FloatArray(9)
                val orientation = FloatArray(3)

                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        SensorManager.getRotationMatrixFromVector(rotationMatrix, event.values)
                        SensorManager.getOrientation(rotationMatrix, orientation)
                        val azimuthRad = orientation[0].toDouble()
                        sinSum += sin(azimuthRad)
                        cosSum += cos(azimuthRad)
                        sampleCount++

                        if (sampleCount >= SAMPLE_COUNT) {
                            sensorManager.unregisterListener(this)
                            if (!continuation.isActive) return

                            if (worstAccuracy <= SensorManager.SENSOR_STATUS_UNRELIABLE) {
                                continuation.resume(null)
                                return
                            }

                            var degrees = Math.toDegrees(atan2(sinSum, cosSum))
                            if (degrees < 0) degrees += 360.0

                            val accuracyDegrees = when (worstAccuracy) {
                                SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> 15.0
                                SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> 25.0
                                else -> 40.0
                            }
                            continuation.resume(
                                HeadingEstimate(degrees, HeadingSource.SENSOR, accuracyDegrees)
                            )
                        }
                    }

                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
                        worstAccuracy = minOf(worstAccuracy, accuracy)
                    }
                }

                sensorManager.registerListener(listener, rotationSensor, SensorManager.SENSOR_DELAY_UI)
                continuation.invokeOnCancellation { sensorManager.unregisterListener(listener) }
            }
        }
    }
}
