package com.cookinlet.belugas

import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreLocation.CLHeading
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

// NOTE: this app is landscape-locked (see iosApp Info.plist), but CLLocationManager reports
// heading relative to portrait "up" unless `headingOrientation` is set to match the active UI
// orientation. Left at the default here since it can't be verified without a physical device —
// if on-device testing shows compass readings off by ~90 degrees, set
// `locationManager.headingOrientation` to the active CLDeviceOrientation before calling
// startUpdatingHeading().
actual class CompassService actual constructor() {
    private val locationManager = CLLocationManager()
    private var delegate: CLLocationManagerDelegateProtocol? = null

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun getCurrentHeading(): HeadingEstimate? {
        if (!CLLocationManager.headingAvailable()) return null

        return suspendCoroutine { continuation ->
            locationManager.requestWhenInUseAuthorization()

            delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
                override fun locationManager(manager: CLLocationManager, didUpdateHeading: CLHeading) {
                    cleanup()

                    val trueHeading = didUpdateHeading.trueHeading
                    val magneticHeading = didUpdateHeading.magneticHeading
                    val accuracy = didUpdateHeading.headingAccuracy
                    val degrees = if (trueHeading >= 0) trueHeading else magneticHeading

                    if (degrees < 0 || accuracy < 0) {
                        // Negative accuracy means uncalibrated/invalid per CoreLocation docs.
                        continuation.resume(null)
                    } else {
                        continuation.resume(
                            HeadingEstimate(degrees, HeadingSource.SENSOR, accuracy.coerceIn(5.0, 45.0))
                        )
                    }
                }

                override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
                    cleanup()
                    continuation.resume(null)
                }

                private fun cleanup() {
                    locationManager.stopUpdatingHeading()
                    locationManager.delegate = null
                    delegate = null
                }
            }

            locationManager.delegate = delegate
            locationManager.startUpdatingHeading()
        }
    }
}
