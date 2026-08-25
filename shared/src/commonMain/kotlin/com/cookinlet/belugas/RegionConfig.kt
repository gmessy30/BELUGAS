package com.cookinlet.belugas

data class RegionConfig(
    val id: String,
    val name: String,
    val mbtilesFile: String,
    val defaultCenterLat: Double,
    val defaultCenterLng: Double,
    val defaultZoom: Double,
    // Geofence Bounding Box (Min/Max Lat & Lng)
    val minLat: Double,
    val maxLat: Double,
    val minLng: Double,
    val maxLng: Double,
    val shorelinePolygon: List<Pair<Double, Double>>
) {
    fun containsLocation(lat: Double, lng: Double): Boolean {
        return lat in minLat..maxLat && lng in minLng..maxLng
    }

    fun isWithinSightline(lat: Double, lng: Double, altitudeMeters: Double): Boolean {
        return GeofenceUtils.isWithin3DFunnel(lat, lng, altitudeMeters, this)
    }
}

// Preset Regional Configurations
object Regions {
    val COOK_INLET = RegionConfig(
        id = "cook_inlet",
        name = "Cook Inlet, Alaska",
        mbtilesFile = "cook_inlet.mbtiles",
        defaultCenterLat = 60.5544,
        defaultCenterLng = -151.2583,
        defaultZoom = 10.0,
        minLat = 59.0, maxLat = 61.5,
        minLng = -154.0, maxLng = -149.0,
        shorelinePolygon = listOf(
            Pair(59.20, -151.90), // Lower Inlet West
            Pair(60.55, -151.27), // Kenai River Mouth
            Pair(60.56, -151.10), // Up Kenai River Channel
            Pair(61.00, -151.15), // Upper Inlet / Tyonek
            Pair(61.28, -149.90), // Anchorage / Knik Arm
            Pair(60.90, -149.80), // Turnagain Arm
            Pair(59.60, -151.50)  // Homer Spit / Kachemak
        )
    )

    val ST_LAWRENCE = RegionConfig(
        id = "st_lawrence",
        name = "St. Lawrence Estuary, Quebec",
        mbtilesFile = "st_lawrence.mbtiles",
        defaultCenterLat = 48.1500,
        defaultCenterLng = -69.7000,
        defaultZoom = 9.5,
        minLat = 47.0, maxLat = 49.5,
        minLng = -71.0, maxLng = -68.0,
        shorelinePolygon = listOf(
            Pair(48.15, -69.70), // Tadoussac
            Pair(48.40, -68.50), // Rimouski
            Pair(47.60, -70.10)  // La Malbaie
        )
    )
    
    val ALL = listOf(COOK_INLET, ST_LAWRENCE)
}
