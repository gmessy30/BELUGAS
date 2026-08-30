package com.cookinlet.belugas

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Coastline data extracted from the notification-zone polygons seeded in
 * supabase/migrations/20260819130000_seed_notification_zones_and_point_presets.sql. Used to
 * default a sighting's sector direction offshore when no (valid) heading was given, and (see
 * isWithinWellSourcedWater below) as a real replacement for GeofenceUtils' old sparse 7-point
 * distance check, which had ~100km+ gaps on a coastline that's actually ~300km long.
 *
 * Only the six well-sourced zones are included here (kenai, lower_inlet_south,
 * ship_creek_knik_arm_anchorage, turnagain_arm_northern, turnagain_arm_mid,
 * turnagain_arm_upper) -- per that migration's own citations, turnagain_arm_southern is
 * mostly a synthetic placeholder wedge and susitna_delta is a star-shaped hull
 * approximation, neither a faithful coastline trace, so a tangent computed from either would
 * be meaningless. entire_inlet is just a bounding rectangle, never real coastline.
 *
 * Each zone stores two vertex lists (lat, lng -- matches HeadingDistance.kt's
 * destinationPoint() convention), both read off by hand against that migration's per-zone
 * citation comments:
 *  - fullRing: the complete closed polygon exactly as seeded (including that zone's
 *    computed-closure vertices and, for `kenai`, its two river-arm spikes), needed intact
 *    for point-in-polygon containment testing.
 *  - coastlineOnly: the same real coastal vertices with computed-closure points and river
 *    spikes removed, used as an open polyline for nearest-segment/tangent computation.
 *
 * Bundled statically rather than fetched from Supabase -- this app is offline-first
 * (OfflineSightingRepository / SyncEngine queue sightings locally and sync when connected),
 * and a sector direction needs to be computable with no network at all.
 */
private data class CoastlineZone(
    val slug: String,
    val fullRing: List<Pair<Double, Double>>,
    val coastlineOnly: List<Pair<Double, Double>>
)

// KENAI: fullRing includes the two 12km-due-west computed closure points (first/last vertex)
// and the Kenai River / Kasilof River arm spikes (traced out to each river's 11-mile point and
// back down the same nodes). coastlineOnly drops both closures and both spikes, leaving the
// real traced shore from East Foreland south past the Kenai and Kasilof river mouths.
private val KENAI = CoastlineZone(
    slug = "kenai",
    fullRing = listOf(
        60.7366758 to -151.5676882,
        60.7368572 to -151.3469160,
        60.7261008 to -151.3937317,
        60.7191148 to -151.4101289,
        60.6935775 to -151.4014373,
        60.6759362 to -151.3878076,
        60.6544696 to -151.3632285,
        60.6322871 to -151.3492429,
        60.5809819 to -151.3298644,
        60.5630859 to -151.3050015,
        60.5553444 to -151.2833585,
        60.5486469 to -151.2621273,
        60.5406886 to -151.2327182,
        60.5250928 to -151.2266779,
        60.5212876 to -151.1731440,
        60.5459337 to -151.1252208, // Kenai River 11mi apex
        60.5212876 to -151.1731440,
        60.5250928 to -151.2266779,
        60.5406886 to -151.2327182,
        60.5486469 to -151.2621273,
        60.5276312 to -151.2730405,
        60.4860598 to -151.2803865,
        60.4673916 to -151.2826138,
        60.4284368 to -151.2884557,
        60.3997196 to -151.2950780,
        60.3860366 to -151.3021596,
        60.3424351 to -151.2890138,
        60.3172346 to -151.2621582,
        60.3102738 to -151.2475703,
        60.3056840 to -151.2222750, // Kasilof River 11mi apex
        60.3102738 to -151.2475703,
        60.3172346 to -151.2621582,
        60.3424351 to -151.2890138,
        60.3860366 to -151.3021596,
        60.3828618 to -151.3218588,
        60.3753466 to -151.3527568,
        60.3429598 to -151.3819584,
        60.3176174 to -151.3816645,
        60.3174391 to -151.5995967,
        60.7366758 to -151.5676882
    ),
    coastlineOnly = listOf(
        60.7368572 to -151.3469160,
        60.7261008 to -151.3937317,
        60.7191148 to -151.4101289,
        60.6935775 to -151.4014373,
        60.6759362 to -151.3878076,
        60.6544696 to -151.3632285,
        60.6322871 to -151.3492429,
        60.5809819 to -151.3298644,
        60.5630859 to -151.3050015,
        60.5553444 to -151.2833585,
        60.5486469 to -151.2621273, // Kenai River mouth
        60.5276312 to -151.2730405,
        60.4860598 to -151.2803865,
        60.4673916 to -151.2826138,
        60.4284368 to -151.2884557,
        60.3997196 to -151.2950780,
        60.3860366 to -151.3021596, // Kasilof River mouth
        60.3828618 to -151.3218588,
        60.3753466 to -151.3527568,
        60.3429598 to -151.3819584,
        60.3176174 to -151.3816645
    )
)

// LOWER INLET SOUTH: fullRing includes a 12km-due-west closure (north end) and a 15km
// bearing-225 closure (south end); everything between is real (filtered to the open-inlet-
// facing shore per that zone's citation).
private val LOWER_INLET_SOUTH = CoastlineZone(
    slug = "lower_inlet_south",
    fullRing = listOf(
        60.3751679 to -151.5710751,
        60.3753466 to -151.3527568,
        60.1824602 to -151.4675938,
        60.0831595 to -151.6290098,
        60.0326754 to -151.6998027,
        59.8856007 to -151.7935463,
        59.7353228 to -151.8433034,
        59.6750709 to -151.4041742,
        59.6491266 to -151.6373241,
        59.6424097 to -151.4669234,
        59.6385074 to -151.5465474,
        59.6379875 to -151.5001532,
        59.6357842 to -151.5291233,
        59.6300155 to -151.4923374,
        59.6208577 to -151.4549495,
        59.6164695 to -151.4489685,
        59.6105027 to -151.4396240,
        59.6091295 to -151.4361317,
        59.6035425 to -151.4248518,
        59.5518838 to -151.3713133,
        59.5461827 to -151.3732553,
        59.4506606 to -151.5609226,
        60.3751679 to -151.5710751
    ),
    coastlineOnly = listOf(
        60.3753466 to -151.3527568,
        60.1824602 to -151.4675938,
        60.0831595 to -151.6290098,
        60.0326754 to -151.6998027,
        59.8856007 to -151.7935463,
        59.7353228 to -151.8433034,
        59.6750709 to -151.4041742,
        59.6491266 to -151.6373241,
        59.6424097 to -151.4669234,
        59.6385074 to -151.5465474,
        59.6379875 to -151.5001532,
        59.6357842 to -151.5291233,
        59.6300155 to -151.4923374,
        59.6208577 to -151.4549495,
        59.6164695 to -151.4489685,
        59.6105027 to -151.4396240,
        59.6091295 to -151.4361317,
        59.6035425 to -151.4248518,
        59.5518838 to -151.3713133,
        59.5461827 to -151.3732553
    )
)

// SHIP CREEK / KNIK ARM / ANCHORAGE: no computed closures per this zone's citation -- the
// whole ring is a "real-data-based approximation" (binned max/min-longitude per 0.02-degree
// latitude band from real OSM coastline ways), covering both the Anchorage and Point
// MacKenzie shores. coastlineOnly is the full real trace, both shores.
private val SHIP_CREEK_KNIK_ARM_ANCHORAGE = CoastlineZone(
    slug = "ship_creek_knik_arm_anchorage",
    fullRing = listOf(
        61.0501897 to -149.7958923,
        61.0702701 to -149.8270671,
        61.0901281 to -149.8686609,
        61.1102239 to -149.9268614,
        61.1305650 to -149.9716155,
        61.2093600 to -149.9234689,
        61.2253725 to -149.8916513,
        61.2498793 to -149.8825525,
        61.2670324 to -149.8637985,
        61.2895950 to -149.8382770,
        61.3073970 to -149.8189027,
        61.3187581 to -149.8009818,
        61.3198466 to -149.9185563,
        61.2905041 to -149.9181032,
        61.2735680 to -149.9222257,
        61.2502319 to -149.9614696,
        61.2491436 to -150.0486695,
        61.2100046 to -149.9233488,
        61.1911934 to -150.0281811,
        61.1773396 to -150.0490272,
        61.1477858 to -150.0488381,
        61.1299173 to -149.9705297,
        61.1097138 to -149.9245010,
        61.0895569 to -149.8654364,
        61.0694978 to -149.8260286,
        61.0501897 to -149.7958923
    ),
    coastlineOnly = listOf(
        61.0501897 to -149.7958923,
        61.0702701 to -149.8270671,
        61.0901281 to -149.8686609,
        61.1102239 to -149.9268614,
        61.1305650 to -149.9716155,
        61.2093600 to -149.9234689,
        61.2253725 to -149.8916513,
        61.2498793 to -149.8825525,
        61.2670324 to -149.8637985,
        61.2895950 to -149.8382770,
        61.3073970 to -149.8189027,
        61.3187581 to -149.8009818,
        61.3198466 to -149.9185563,
        61.2905041 to -149.9181032,
        61.2735680 to -149.9222257,
        61.2502319 to -149.9614696,
        61.2491436 to -150.0486695,
        61.2100046 to -149.9233488,
        61.1911934 to -150.0281811,
        61.1773396 to -150.0490272,
        61.1477858 to -150.0488381,
        61.1299173 to -149.9705297,
        61.1097138 to -149.9245010,
        61.0895569 to -149.8654364,
        61.0694978 to -149.8260286
    )
)

// TURNAGAIN ARM NORTHERN: same binned-real-data approach, no computed closures.
private val TURNAGAIN_ARM_NORTHERN = CoastlineZone(
    slug = "turnagain_arm_northern",
    fullRing = listOf(
        60.9501879 to -149.8956907,
        60.9687547 to -149.8290968,
        61.0175752 to -149.7397115,
        60.9994750 to -149.6435683,
        60.9838916 to -149.5311968,
        60.9727474 to -149.4671907,
        60.9460508 to -149.3796517,
        60.8946491 to -149.3539654,
        60.9042512 to -149.4400256,
        60.9256969 to -149.5306776,
        60.9209726 to -149.6458563,
        60.9554544 to -149.7182679,
        60.9661930 to -149.8250628,
        60.9248332 to -149.9186612,
        60.9501879 to -149.8956907
    ),
    coastlineOnly = listOf(
        60.9501879 to -149.8956907,
        60.9687547 to -149.8290968,
        61.0175752 to -149.7397115,
        60.9994750 to -149.6435683,
        60.9838916 to -149.5311968,
        60.9727474 to -149.4671907,
        60.9460508 to -149.3796517,
        60.8946491 to -149.3539654,
        60.9042512 to -149.4400256,
        60.9256969 to -149.5306776,
        60.9209726 to -149.6458563,
        60.9554544 to -149.7182679,
        60.9661930 to -149.8250628,
        60.9248332 to -149.9186612
    )
)

// TURNAGAIN ARM MID: same binned-real-data approach, no computed closures.
private val TURNAGAIN_ARM_MID = CoastlineZone(
    slug = "turnagain_arm_mid",
    fullRing = listOf(
        60.9460508 to -149.3796517,
        60.9370152 to -149.2623142,
        60.9448624 to -149.1938707,
        60.9135708 to -149.0991900,
        60.8583638 to -149.0162893,
        60.8197676 to -149.0002884,
        60.8642041 to -149.0811245,
        60.8868840 to -149.1737297,
        60.8947429 to -149.2741072,
        60.8946491 to -149.3539654,
        60.9460508 to -149.3796517
    ),
    coastlineOnly = listOf(
        60.9460508 to -149.3796517,
        60.9370152 to -149.2623142,
        60.9448624 to -149.1938707,
        60.9135708 to -149.0991900,
        60.8583638 to -149.0162893,
        60.8197676 to -149.0002884,
        60.8642041 to -149.0811245,
        60.8868840 to -149.1737297,
        60.8947429 to -149.2741072,
        60.8946491 to -149.3539654
    )
)

// TURNAGAIN ARM UPPER: same binned-real-data approach, plus the Twentymile River mouth as a
// real single-point anchor (not a spike -- no retrace pattern, so it stays in coastlineOnly).
private val TURNAGAIN_ARM_UPPER = CoastlineZone(
    slug = "turnagain_arm_upper",
    fullRing = listOf(
        60.9221044 to -149.1389043,
        60.9012904 to -149.0748797,
        60.8583638 to -149.0162893,
        60.8417766 to -149.0075211, // Twentymile River mouth
        60.8197676 to -149.0002884,
        60.8642041 to -149.0811245,
        60.9221044 to -149.1389043
    ),
    coastlineOnly = listOf(
        60.9221044 to -149.1389043,
        60.9012904 to -149.0748797,
        60.8583638 to -149.0162893,
        60.8417766 to -149.0075211,
        60.8197676 to -149.0002884,
        60.8642041 to -149.0811245
    )
)

private val WELL_SOURCED_ZONES = listOf(
    KENAI,
    LOWER_INLET_SOUTH,
    SHIP_CREEK_KNIK_ARM_ANCHORAGE,
    TURNAGAIN_ARM_NORTHERN,
    TURNAGAIN_ARM_MID,
    TURNAGAIN_ARM_UPPER
)

private const val METERS_PER_DEGREE_LAT = 111320.0

private fun metersPerDegreeLng(lat: Double): Double = METERS_PER_DEGREE_LAT * cos(lat * PI / 180.0)

// Standard PNPOLY ray-casting test, operating directly on lat/lng as a planar (lng=x, lat=y)
// coordinate pair -- consistent with how these rings are stored server-side as
// geometry(Polygon, 4326) (planar, not geography), so this matches rather than reinterprets
// their semantics.
private fun pointInPolygon(lat: Double, lng: Double, ring: List<Pair<Double, Double>>): Boolean {
    var inside = false
    var j = ring.size - 1
    for (i in ring.indices) {
        val (latI, lngI) = ring[i]
        val (latJ, lngJ) = ring[j]
        if ((lngI > lng) != (lngJ > lng)) {
            val intersectLat = latI + (lng - lngI) / (lngJ - lngI) * (latJ - latI)
            if (lat < intersectLat) inside = !inside
        }
        j = i
    }
    return inside
}

private fun initialBearingDegrees(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val phi1 = lat1 * PI / 180.0
    val phi2 = lat2 * PI / 180.0
    val deltaLambda = (lng2 - lng1) * PI / 180.0
    val y = sin(deltaLambda) * cos(phi2)
    val x = cos(phi1) * sin(phi2) - sin(phi1) * cos(phi2) * cos(deltaLambda)
    val theta = atan2(y, x)
    return (theta * 180.0 / PI + 360.0) % 360.0
}

private data class NearestSegmentResult(
    val nearestLat: Double,
    val nearestLng: Double,
    val bearingDegrees: Double,
    val distanceMeters: Double
)

// Nearest point across every segment of the polyline (not just nearest vertex), via a local
// flat-meters projection -- accurate enough since individual coastline segments here span at
// most a few km, well within where a flat-earth approximation holds.
private fun nearestSegment(lat: Double, lng: Double, polyline: List<Pair<Double, Double>>): NearestSegmentResult? {
    if (polyline.size < 2) return null
    var best: NearestSegmentResult? = null
    var bestDistSq = Double.MAX_VALUE
    val mPerLng = metersPerDegreeLng(lat)

    for (i in 0 until polyline.size - 1) {
        val (lat1, lng1) = polyline[i]
        val (lat2, lng2) = polyline[i + 1]

        val x1 = lng1 * mPerLng; val y1 = lat1 * METERS_PER_DEGREE_LAT
        val x2 = lng2 * mPerLng; val y2 = lat2 * METERS_PER_DEGREE_LAT
        val px = lng * mPerLng; val py = lat * METERS_PER_DEGREE_LAT

        val dx = x2 - x1
        val dy = y2 - y1
        val lenSq = dx * dx + dy * dy
        val t = if (lenSq > 0.0) (((px - x1) * dx + (py - y1) * dy) / lenSq).coerceIn(0.0, 1.0) else 0.0

        val nearX = x1 + t * dx
        val nearY = y1 + t * dy
        val distSq = (px - nearX) * (px - nearX) + (py - nearY) * (py - nearY)

        if (distSq < bestDistSq) {
            bestDistSq = distSq
            best = NearestSegmentResult(
                nearestLat = nearY / METERS_PER_DEGREE_LAT,
                nearestLng = nearX / mPerLng,
                bearingDegrees = initialBearingDegrees(lat1, lng1, lat2, lng2),
                distanceMeters = sqrt(distSq)
            )
        }
    }
    return best
}

/**
 * Default sector direction when a sighting has no (valid) heading: 90 degrees from the
 * tangent of the nearest coastline segment, on whichever side falls back inside the
 * containing zone's water polygon. Falls back to a coarse "bearing toward the nearest
 * GeofenceUtils water reference point" when the observer isn't inside any well-sourced zone
 * (e.g. inside turnagain_arm_southern/susitna_delta's un-sourced area, or open water far from
 * any zone) -- those existing ~7 points represent water/inlet locations, so the bearing
 * toward the nearest one is a reasonable coarse "roughly toward water" proxy.
 */
fun computeDefaultOffshoreHeadingDegrees(lat: Double, lng: Double): Double {
    for (zone in WELL_SOURCED_ZONES) {
        if (!pointInPolygon(lat, lng, zone.fullRing)) continue

        val segment = nearestSegment(lat, lng, zone.coastlineOnly) ?: continue
        val candidateA = (segment.bearingDegrees + 90.0) % 360.0
        val candidateB = (segment.bearingDegrees + 270.0) % 360.0

        val testA = destinationPoint(segment.nearestLat, segment.nearestLng, candidateA, 50.0)
        val testB = destinationPoint(segment.nearestLat, segment.nearestLng, candidateB, 50.0)

        val aInside = pointInPolygon(testA.first, testA.second, zone.fullRing)
        val bInside = pointInPolygon(testB.first, testB.second, zone.fullRing)

        if (aInside && !bInside) return candidateA
        if (bInside && !aInside) return candidateB
        // Both or neither landed inside -- shouldn't happen for a correctly-extracted ring,
        // but rather than guess a possibly-wrong side, fall through to the coarse fallback.
        break
    }

    val nearestWaterPoint = GeofenceUtils.COOK_INLET_WATER_BASE.minByOrNull { (waterLat, waterLng) ->
        val dLat = (waterLat - lat) * METERS_PER_DEGREE_LAT
        val dLng = (waterLng - lng) * metersPerDegreeLng(lat)
        dLat * dLat + dLng * dLng
    } ?: return 0.0

    return initialBearingDegrees(lat, lng, nearestWaterPoint.first, nearestWaterPoint.second)
}

/**
 * Whether an explicit heading from this observer position points at land instead of water.
 * Only judgeable when the observer is inside a well-sourced zone (same containment check as
 * [computeDefaultOffshoreHeadingDegrees]); if not, there's no reliable data to override the
 * given heading with, so this trusts it rather than guessing.
 */
fun headingPointsAtLand(lat: Double, lng: Double, headingDegrees: Double): Boolean {
    for (zone in WELL_SOURCED_ZONES) {
        if (!pointInPolygon(lat, lng, zone.fullRing)) continue
        val (testLat, testLng) = destinationPoint(lat, lng, headingDegrees, 200.0)
        return !pointInPolygon(testLat, testLng, zone.fullRing)
    }
    return false
}

// KENAI.fullRing's river "spikes" (see that zone's header comment) trace up the Kenai/Kasilof
// River centerlines and directly back down the same nodes -- a zero-width slit with no
// interior area at all. A plain point-in-polygon test can never validate a point near either
// river (e.g. a park on the riverbank, not standing exactly on the GPS-traced centerline)
// because of this, so isWithinWellSourcedWater below checks proximity to these centerlines
// directly, the same way it checks proximity to real coastline. Same literal coordinates
// already embedded in KENAI.fullRing (indices 11-15 and 25-29 respectively) -- kept as their
// own named lists here since they're conceptually a distinct, reusable "real line to measure
// distance to", not restated by re-deriving or guessing new numbers.
private val KENAI_RIVER_CENTERLINE = listOf(
    60.5486469 to -151.2621273,
    60.5406886 to -151.2327182,
    60.5250928 to -151.2266779,
    60.5212876 to -151.1731440,
    60.5459337 to -151.1252208
)

private val KASILOF_RIVER_CENTERLINE = listOf(
    60.3860366 to -151.3021596,
    60.3424351 to -151.2890138,
    60.3172346 to -151.2621582,
    60.3102738 to -151.2475703,
    60.3056840 to -151.2222750
)

// Every real linestring (coastline +, for kenai, its two river centerlines) a given
// well-sourced zone can be measured against for the buffer-distance fallback below.
private fun realLinesForZone(zone: CoastlineZone): List<List<Pair<Double, Double>>> {
    val lines = mutableListOf(zone.coastlineOnly)
    if (zone.slug == "kenai") {
        lines.add(KENAI_RIVER_CENTERLINE)
        lines.add(KASILOF_RIVER_CENTERLINE)
    }
    return lines
}

/**
 * Public accessor for a watched zone's river centerlines, for rendering (see
 * SightingsMapScreen's "Belugas present" river shading). A zone's polygon boundary traces each
 * river as a there-and-back spike (see KENAI's doc comment above) so its interior/outline can
 * never actually render as a colored river on a map -- these clean, single-direction centerlines
 * are what a river-specific LineLayer should draw instead. Empty for any zone without one.
 */
fun riverCenterlinesForZoneSlug(slug: String): List<List<Pair<Double, Double>>> = when (slug) {
    "kenai" -> listOf(KENAI_RIVER_CENTERLINE, KASILOF_RIVER_CENTERLINE)
    else -> emptyList()
}

// How far out a well-sourced zone's real data is considered authoritative for. Generous
// enough to cover a zone's own mapped offshore extent (the computed closure edges reach
// 12-15km out) plus real margin, without being so wide it starts confidently rejecting points
// that are actually just outside well-sourced coverage entirely (e.g. in
// turnagain_arm_southern or susitna_delta, which isWithinWellSourcedWater has no data for).
private const val ZONE_AUTHORITATIVE_REACH_KM = 25.0

/**
 * Real-polygon-and-coastline replacement for GeofenceUtils' old sparse 7-point distance check,
 * for the Cook Inlet zones this file actually has precise coastline data for (see the
 * WELL_SOURCED_ZONES doc comment for which -- turnagain_arm_southern and susitna_delta are
 * NOT covered). Returns null, not false, when the point isn't within reach of any well-sourced
 * zone at all: this function having no data isn't evidence the point is invalid, and callers
 * must fall back to a coarser check in that case rather than treat null as a rejection.
 *
 * Ground truth in tiers, keeping the same altitude-scaled buffer semantics
 * GeofenceUtils.isWithin3DFunnel already used:
 *  1. The point falls inside a zone's real water polygon -> unambiguously valid regardless of
 *     altitude (there's no more direct evidence than "the point IS water").
 *  2. Outside every polygon, but within the altitude-scaled buffer distance of the nearest
 *     real coastline or river-centerline segment (river centerlines specifically because a
 *     zone's river spike has zero interior area, per KENAI_RIVER_CENTERLINE's comment above)
 *     -> valid.
 *  3. Neither, but still within ZONE_AUTHORITATIVE_REACH_KM of a well-sourced zone -> a
 *     confident rejection (false, not null) -- we have real data here and it's genuinely too
 *     far from water.
 *  4. Out of reach of every well-sourced zone -> null, defer to the sparse-point fallback.
 */
fun isWithinWellSourcedWater(lat: Double, lng: Double, altitudeMeters: Double): Boolean? {
    val allowedBufferKm = if (altitudeMeters > 50.0) {
        0.8 + ((altitudeMeters / 100.0) * 1.2)
    } else {
        0.8
    }

    var withinReachOfAnyZone = false

    for (zone in WELL_SOURCED_ZONES) {
        if (pointInPolygon(lat, lng, zone.fullRing)) return true

        var nearestKm = Double.MAX_VALUE
        for (line in realLinesForZone(zone)) {
            val segment = nearestSegment(lat, lng, line) ?: continue
            val distKm = segment.distanceMeters / 1000.0
            if (distKm < nearestKm) nearestKm = distKm
        }

        if (nearestKm <= allowedBufferKm) return true
        if (nearestKm <= ZONE_AUTHORITATIVE_REACH_KM) withinReachOfAnyZone = true
    }

    return if (withinReachOfAnyZone) false else null
}
