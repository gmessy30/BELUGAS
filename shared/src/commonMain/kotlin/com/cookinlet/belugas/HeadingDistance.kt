package com.cookinlet.belugas

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

enum class HeadingSource { SENSOR, MANUAL }

/**
 * A compass bearing (0..359.99, from true/magnetic north) from the observer to the sighting.
 */
data class HeadingEstimate(
    val degrees: Double,
    val source: HeadingSource,
    // null for MANUAL; degrees of uncertainty reported by the platform sensor for SENSOR
    val accuracyDegrees: Double? = null
)

enum class DistanceBucket(val label: String) {
    CLOSE("Close"),
    MEDIUM("Medium"),
    FAR("Far");

    /**
     * Shore-based sightings use tighter ranges (naked-eye/binocular spotting distances);
     * aerial/boat observers can credibly place something much farther out.
     */
    fun radiusMeters(isAerial: Boolean): Double = if (isAerial) {
        when (this) {
            CLOSE -> 300.0
            MEDIUM -> 1000.0
            FAR -> 3000.0
        }
    } else {
        when (this) {
            CLOSE -> 150.0
            MEDIUM -> 500.0
            FAR -> 1200.0
        }
    }

    /**
     * Comparator + distance (e.g. "~500m"), no bucket name -- compact enough that all three
     * chips reliably fit on one line even in landscape's reduced dialog height (a wrapped
     * second line can get squeezed out of the dialog's constrained vertical space there).
     */
    fun shortLabel(isAerial: Boolean): String {
        val meters = radiusMeters(isAerial)
        val distanceText = if (meters >= 1000.0) "${(meters / 1000.0)}km" else "${meters.toInt()}m"
        val comparator = when (this) {
            CLOSE -> "<"
            MEDIUM -> "~"
            FAR -> ">"
        }
        return "$comparator$distanceText"
    }

    companion object {
        fun fromStringOrNull(value: String?): DistanceBucket? =
            entries.firstOrNull { it.name == value }
    }
}

/**
 * Destination point given a start coordinate, bearing, and distance (spherical earth model).
 * Needed instead of flat lat/lng math because 1 degree of longitude shrinks a lot away from
 * the equator (Cook Inlet sits at ~60N, where it's about half the size it is at the equator).
 */
fun destinationPoint(lat: Double, lng: Double, bearingDegrees: Double, distanceMeters: Double): Pair<Double, Double> {
    val earthRadiusMeters = 6371000.0
    val angularDistance = distanceMeters / earthRadiusMeters
    val bearingRad = bearingDegrees * PI / 180.0
    val lat1 = lat * PI / 180.0
    val lng1 = lng * PI / 180.0

    val lat2 = asin(sin(lat1) * cos(angularDistance) + cos(lat1) * sin(angularDistance) * cos(bearingRad))
    val lng2 = lng1 + atan2(
        sin(bearingRad) * sin(angularDistance) * cos(lat1),
        cos(angularDistance) - sin(lat1) * sin(lat2)
    )

    return Pair(lat2 * 180.0 / PI, lng2 * 180.0 / PI)
}

/**
 * Whether the far edge of a heading+distance sector still falls within the region's water
 * geofence — reuses the same altitude-aware shoreline buffer already applied to pin locations,
 * so shore vs. aerial observer status drives the same orientation constraint here too.
 */
fun sectorEndpointWithinGeofence(
    originLat: Double,
    originLng: Double,
    heading: HeadingEstimate,
    radiusMeters: Double,
    altitudeMeters: Double,
    region: RegionConfig
): Boolean {
    val (endLat, endLng) = destinationPoint(originLat, originLng, heading.degrees, radiusMeters)
    return GeofenceUtils.isWithin3DFunnel(endLat, endLng, altitudeMeters, region)
}

/**
 * Builds a plain uncertainty-circle GeoJSON Polygon feature around [lat]/[lng] at
 * [radiusMeters] -- the whale-position redesign's replacement for the old heading/distance
 * sector wedge (buildSectorGeoJsonFeature, removed): a wedge's apex reveals where the observer
 * stood, which this design specifically avoids storing at all, so the map can only ever draw a
 * shape centered on the (already anonymous) estimated position.
 */
fun buildCircleGeoJsonFeature(
    lat: Double,
    lng: Double,
    radiusMeters: Double,
    propertiesJson: String = "{}",
    segments: Int = 32
): String {
    val ring = (0..segments).joinToString(", ") { i ->
        val bearing = 360.0 * i / segments
        val (pointLat, pointLng) = destinationPoint(lat, lng, bearing, radiusMeters)
        "[$pointLng, $pointLat]"
    }

    return """
    {
      "type": "Feature",
      "geometry": {
        "type": "Polygon",
        "coordinates": [[ $ring ]]
      },
      "properties": $propertiesJson
    }
    """.trimIndent()
}

// Item 34: "2 white, 0 grey, 2 calves, 0 unknown" -- shared by LoggingScreen.kt/
// ManualLoggingScreen.kt's own pre-submit confirmation summary (and by webapp/js/submit-view.js's
// formatWhaleCountsSummary, the same format ported to the web app for parity).
fun formatWhaleCountsSummary(whites: Int, greys: Int, calves: Int, unknown: Int): String =
    "$whites white, $greys grey, $calves calves, $unknown unknown"

// Compass isn't accurate enough to justify rendering a precise degree value -- the map arrow
// always snaps to the nearest of 8 compass points, even though the stored travelBearingDegrees
// keeps its full precision (this only affects display).
fun snapToNearestCompass8Degrees(bearingDegrees: Double): Double {
    val snapped = kotlin.math.round(bearingDegrees / 45.0) * 45.0
    return ((snapped % 360.0) + 360.0) % 360.0
}

/**
 * Builds a plain line stub (a single GeoJSON LineString feature, no arrowhead) pointing along
 * [bearingDegrees] from [lat]/[lng] -- drawn only where a travel bearing exists on a sighting; a
 * plain dot otherwise. Reads like a handle on the sighting's own point marker (pan/lollipop
 * silhouette: dot plus handle) rather than a second, separate arrow glyph -- cleaner at map scale
 * and less visually noisy once sightings cluster than the previous shaft-plus-barbs arrow this
 * replaced. [bearingDegrees] is expected to already be snapped via [snapToNearestCompass8Degrees].
 */
fun buildTravelStubGeoJsonFeature(
    lat: Double,
    lng: Double,
    bearingDegrees: Double,
    propertiesJson: String = "{}",
    stubLengthMeters: Double = 40.0
): String {
    val (tipLat, tipLng) = destinationPoint(lat, lng, bearingDegrees, stubLengthMeters)

    return """
    {
      "type": "Feature",
      "geometry": {
        "type": "LineString",
        "coordinates": [ [$lng, $lat], [$tipLng, $tipLat] ]
      },
      "properties": $propertiesJson
    }
    """.trimIndent()
}
