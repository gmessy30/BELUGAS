package com.cookinlet.belugas

import kotlin.math.*

object GeofenceUtils {

    // Ground-level water line & active tidal river channel points. Sparse (only 7 points
    // across a coastline that's actually ~300km long) -- kept only as the last-resort
    // fallback isWithin3DFunnel now uses when CoastlineGeometry's real polygon data doesn't
    // cover a location (see that function's doc comment). Also referenced directly by
    // CoastlineGeometry.computeDefaultOffshoreHeadingDegrees's own coarse fallback.
    val COOK_INLET_WATER_BASE = listOf(
        Pair(59.20, -151.90), // Lower Inlet West
        Pair(60.5544, -151.2583), // Kenai River Mouth
        Pair(60.5500, -151.1500), // Lower Kenai River Channel
        Pair(61.00, -151.15), // Upper Inlet / Tyonek
        Pair(61.28, -149.90), // Anchorage / Knik Arm
        Pair(60.90, -149.80), // Turnagain Arm
        Pair(59.60, -151.50)  // Homer Spit / Kachemak
    )

    // The sparse check's original ground-level buffer (0.8km) produced real false-positive
    // rejections: valid on-water sightings falling in the ~100km+ gaps between these 7 points
    // (e.g. Cunningham Park near Kenai River mile 6) were rejected outright. Now that
    // CoastlineGeometry's real polygon data covers most of Cook Inlet's populated coastline
    // and this sparse check is only the fallback for what it doesn't (turnagain_arm_southern,
    // susitna_delta, and genuinely out-of-coverage points), it's widened to be more forgiving
    // rather than rejecting outright -- not a rigorously derived value, just a deliberately
    // more generous stand-in until those remaining zones get real coastline data too.
    private const val FALLBACK_BASE_BUFFER_KM = 3.0

    /**
     * Evaluates whether a location is within sightline of beluga habitat.
     *
     * Tries CoastlineGeometry's real polygon/coastline data first (Cook Inlet only -- other
     * regions like St. Lawrence have none) since it's far more precise than the sparse
     * distance check below. Only falls through to the sparse check when that real data has no
     * coverage for this point (a different region entirely, or one of the not-yet-mapped Cook
     * Inlet zones) -- see [CoastlineGeometry.isWithinWellSourcedWater]'s doc comment for the
     * full tier breakdown.
     *
     * At ground level (<50m altitude), the sparse fallback allows a [FALLBACK_BASE_BUFFER_KM]
     * buffer around water/rivers. At altitude (aircraft / bluffs), it expands by ~1.2 km per
     * 100m of elevation, same as before.
     */
    fun isWithin3DFunnel(
        lat: Double,
        lng: Double,
        altitudeMeters: Double,
        region: RegionConfig
    ): Boolean {
        if (region.id == Regions.COOK_INLET.id) {
            val wellSourced = isWithinWellSourcedWater(lat, lng, altitudeMeters)
            if (wellSourced != null) return wellSourced
        }

        // Calculate allowed horizontal buffer based on altitude
        val allowedBufferKm = if (altitudeMeters > 50.0) {
            FALLBACK_BASE_BUFFER_KM + ((altitudeMeters / 100.0) * 1.2) // Expands line-of-sight cone for aircraft
        } else {
            FALLBACK_BASE_BUFFER_KM
        }

        val minDistanceKm = getMinDistanceToWaterKm(lat, lng, region.shorelinePolygon)
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
