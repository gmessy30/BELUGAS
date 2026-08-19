package com.cookinlet.belugas

import android.annotation.SuppressLint
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.tasks.await

actual class LocationService actual constructor() {
    
    @SuppressLint("MissingPermission")
    actual suspend fun getCurrentLocation(): LocationCoordinates? {
        return try {
            val fusedClient = LocationServices.getFusedLocationProviderClient(androidContext)
            val location = fusedClient.getCurrentLocation(
                Priority.PRIORITY_HIGH_ACCURACY,
                null
            ).await()

            location?.let {
                LocationCoordinates(it.latitude, it.longitude, it.altitude)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
}
