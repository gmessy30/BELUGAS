package com.cookinlet.belugas

import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.*

object GeofenceUtils {

    // How long a caller's brief "checking..." state is willing to wait for the online
    // coastline-channel fallback (see isWithinCoastlineChannelFallback) before giving up.
    // Deliberately short and used in place of an explicit connectivity check: an offline
    // device just won't get an answer within this window and falls through to the
    // synchronous check's original rejection, exactly as if this fallback didn't exist,
    // rather than leaving a submit button showing a spinner indefinitely.
    private const val COASTLINE_CHANNEL_FALLBACK_TIMEOUT_MS = 6000L

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

    /**
     * Best-effort online-only upgrade for a point [isWithin3DFunnel] has already rejected --
     * never called on its own, only from a caller's failure branch (see ManualLoggingScreen/
     * LoggingScreen's SUBMIT handlers). Delegates to the real-coastline-curve RPC
     * (SupabaseApi.isPointWithinCoastlineChannel / supabase/migrations/
     * 20260830000000_add_coastline_traces.sql) that covers genuinely far-offshore points
     * neither the well-sourced zones nor the sparse fallback above have data for.
     *
     * Bounded by [COASTLINE_CHANNEL_FALLBACK_TIMEOUT_MS] in place of an explicit
     * connectivity check -- an offline device simply doesn't get an answer in time and this
     * returns false, identical to the fallback not existing, so callers should treat false
     * here the same as [isWithin3DFunnel]'s own false: a real rejection (or an inconclusive
     * offline attempt), not a distinguishable error case.
     */
    suspend fun isWithinCoastlineChannelFallback(lat: Double, lng: Double): Boolean {
        val result = withTimeoutOrNull(COASTLINE_CHANNEL_FALLBACK_TIMEOUT_MS) {
            SupabaseApi.isPointWithinCoastlineChannel(lat, lng)
        }
        return result == true
    }

    // Ceiling on the buffer used to verify a whale position, regardless of the row's own
    // uncertainty_radius_meters. Without this, is_geofence_verified would have a perverse
    // incentive built in: on a PIN row the radius is a value the *user* chose, so an uncapped
    // buffer means claiming more uncertainty makes verification EASIER ("I'm very unsure"
    // becomes "verified"). The stored uncertaintyRadiusMeters is untouched by this cap -- only
    // the buffer used for this specific check is limited.
    private const val WHALE_POSITION_VERIFY_BUFFER_CAP_METERS = 1000.0

    /**
     * Whether a whale position (see SightingRecord.whaleLat/whaleLng) is close enough to real
     * water to count as geofence-verified, given its own [uncertaintyRadiusMeters] -- capped at
     * [WHALE_POSITION_VERIFY_BUFFER_CAP_METERS] first (see that constant's comment). Unlike
     * isWithin3DFunnel, there's no altitude term: the point being validated is the animal's own
     * estimated position, not an observer's elevated sightline, so CoastlineGeometry.
     * isWithinGeofenceBuffer's plain buffer-distance tiers apply directly.
     *
     * Returns null exactly when isWithinGeofenceBuffer does -- out of reach of every
     * well-sourced zone -- so a caller should fall through to the same online coastline-channel
     * RPC fallback used elsewhere, not treat null as a rejection.
     */
    fun isWhalePositionVerified(lat: Double, lng: Double, uncertaintyRadiusMeters: Double): Boolean? {
        val cappedBufferMeters = minOf(uncertaintyRadiusMeters, WHALE_POSITION_VERIFY_BUFFER_CAP_METERS)
        return isWithinGeofenceBuffer(lat, lng, cappedBufferMeters)
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
