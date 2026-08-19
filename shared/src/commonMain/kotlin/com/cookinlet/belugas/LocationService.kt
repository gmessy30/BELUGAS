package com.cookinlet.belugas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

data class LocationCoordinates(
    val latitude: Double,
    val longitude: Double,
    val altitudeMeters: Double = 0.0
)

expect class LocationService() {
    suspend fun getCurrentLocation(): LocationCoordinates?
}

@Composable
fun rememberLocationService(): LocationService = remember { LocationService() }
