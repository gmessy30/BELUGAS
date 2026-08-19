package com.cookinlet.belugas

import kotlin.math.*

object GeofenceUtils {

    // Ground-level water line & active tidal river channel points
    val COOK_INLET_WATER_BASE = listOf(
        Pair(59.20, -151.90), // Lower Inlet West
        Pair(60.5544, -151.2583), // Kenai River Mouth
        Pair(60.5500, -151.1500), // Lower Kenai River Channel
        Pair(61.00, -151.15), // Upper Inlet / Tyonek
        Pair(61.28, -149.90), // Anchorage / Knik Arm
        Pair(60.90, -149.80), // Turnagain Arm
        Pair(59.60, -151.50)  // Homer Spit / Kachemak
    )

    /**
     * Evaluates whether a location is within sightline of beluga habitat.
     * At ground level (<50m altitude), allows a tight 0.8 km buffer around water/rivers.
     * At altitude (aircraft / bluffs), expands the buffer by ~1.2 km per 100m of elevation.
     */
    fun isWithin3DFunnel(
        lat: Double,
        lng: Double,
        altitudeMeters: Double,
        waterBasePoints: List<Pair<Double, Double>> = COOK_INLET_WATER_BASE
    ): Boolean {
        // Calculate allowed horizontal buffer based on altitude
        val allowedBufferKm = if (altitudeMeters > 50.0) {
            0.8 + ((altitudeMeters / 100.0) * 1.2) // Expands line-of-sight cone for aircraft
        } else {
            0.8 // Strict 800m ground-level buffer around shoreline/river mouth
        }

        val minDistanceKm = getMinDistanceToWaterKm(lat, lng, waterBasePoints)
        return minDistanceKm <= allowedBufferKm
    }

    // Haversine distance from point to nearest shoreline/river coordinate
    private fun getMinDistanceToWaterKm(
        lat: Double,
        lng: Double,
        points: List<Pair<Double, Double>>
    ): Double {
        var minDistance = Double.MAX_VALUE
        for (pt in points) {
            val dist = haversineKm(lat, lng, pt.first, pt.second)
            if (dist < minDistance) {
                minDistance = dist
            }
        }
        return minDistance
    }

    private fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0 // Earth's radius in kilometers
        val dLat = (lat2 - lat1).toRadians()
        val dLon = (lon2 - lon1).toRadians()
        val a = sin(dLat / 2).pow(2) +
                cos(lat1.toRadians()) * cos(lat2.toRadians()) *
                sin(dLon / 2).pow(2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return r * c
    }

    private fun Double.toRadians(): Double = this * (PI / 180.0)
}
