package com.cookinlet.belugas

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import platform.CoreLocation.CLLocation
import platform.CoreLocation.CLLocationManager
import platform.CoreLocation.CLLocationManagerDelegateProtocol
import platform.Foundation.NSError
import platform.darwin.NSObject
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

actual class LocationService actual constructor() {
    private val locationManager = CLLocationManager()
    private var delegate: CLLocationManagerDelegateProtocol? = null

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun getCurrentLocation(): LocationCoordinates? = suspendCoroutine { continuation ->
        locationManager.requestWhenInUseAuthorization()
        
        delegate = object : NSObject(), CLLocationManagerDelegateProtocol {
            override fun locationManager(manager: CLLocationManager, didUpdateLocations: List<*>) {
                val location = didUpdateLocations.lastOrNull() as? CLLocation
                if (location != null) {
                    val coords = location.coordinate.useContents {
                        LocationCoordinates(latitude, longitude, location.altitude)
                    }
                    continuation.resume(coords)
                } else {
                    continuation.resume(null)
                }
                cleanup()
            }

            override fun locationManager(manager: CLLocationManager, didFailWithError: NSError) {
                continuation.resume(null)
                cleanup()
            }

            private fun cleanup() {
                locationManager.delegate = null
                delegate = null
            }
        }

        locationManager.delegate = delegate
        locationManager.requestLocation()
    }
}
