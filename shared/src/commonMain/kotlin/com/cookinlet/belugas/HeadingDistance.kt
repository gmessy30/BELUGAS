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
) {
    // Confidence -> sector half-width. Manual entries and noisy sensor readings get a wide
    // cone; a clean compass reading narrows it.
    val sectorHalfWidthDegrees: Double
        get() = when (source) {
            HeadingSource.MANUAL -> 45.0
            HeadingSource.SENSOR -> (accuracyDegrees ?: 30.0).coerceIn(10.0, 45.0)
        }
}

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
 * Builds a filled-wedge (annular sector) GeoJSON Polygon feature: from the origin out to an arc
 * at [radiusMeters], spanning [heading]'s confidence-driven half-width on either side of its
 * bearing.
 */
fun buildSectorGeoJsonFeature(
    originLat: Double,
    originLng: Double,
    heading: HeadingEstimate,
    radiusMeters: Double,
    propertiesJson: String = "{}",
    segments: Int = 16
): String {
    val halfWidth = heading.sectorHalfWidthDegrees
    val startBearing = heading.degrees - halfWidth
    val sweep = halfWidth * 2.0

    val arcCoordinates = (0..segments).joinToString(", ") { i ->
        val bearing = startBearing + (sweep * i / segments)
        val (pointLat, pointLng) = destinationPoint(originLat, originLng, bearing, radiusMeters)
        "[$pointLng, $pointLat]"
    }

    return """
    {
      "type": "Feature",
      "geometry": {
        "type": "Polygon",
        "coordinates": [[ [$originLng, $originLat], $arcCoordinates, [$originLng, $originLat] ]]
      },
      "properties": $propertiesJson
    }
    """.trimIndent()
}
