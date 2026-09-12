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
// their semantics. internal (not private) so TierClaimScreen's departure-report polygon check
// can reuse the same ray-cast instead of a second copy of this algorithm.
internal fun pointInPolygon(lat: Double, lng: Double, ring: List<Pair<Double, Double>>): Boolean {
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


// KENAI.fullRing's river "spikes" (see that zone's header comment) trace up the Kenai/Kasilof
// River centerlines and directly back down the same nodes -- a zero-width slit with no
// interior area at all. A plain point-in-polygon test can never validate a point near either
// river (e.g. a park on the riverbank, not standing exactly on the GPS-traced centerline)
// because of this, so isWithinWellSourcedWater below checks proximity to these centerlines
// directly, the same way it checks proximity to real coastline.
//
// REVISED 2026-08-31: upgraded from the original 5-point OSM-derived approximation (which is
// still what KENAI.fullRing's own spike traces -- that only needs to be a degenerate slit for
// containment purposes, no benefit to matching this file's higher detail) to the Kenai
// Peninsula Borough's own "KPB 21.18 Anadromous Streams" layer (services.arcgis.com/
// ba4DH9pIcqkXJVfl/.../KPB_2118_view/FeatureServer/0, a real esriGeometryPolyline dataset tied
// to Alaska Fish & Game's Anadromous Waters Catalog), queried 2026-08-30. Cross-validated
// against the original 5 points before replacing them: every original point landed within
// 58-161m (Kenai) / 8-122m (Kasilof) of this new data -- independent confirmation the two
// sources agree, not just a blind swap. Also extends real coverage much further upstream than
// the old 11-mile cutoff (Kenai to ~-150.51, Kasilof to ~-151.165), narrowing the
// low-confidence gap further inland.
//
// RE-REVISED 2026-08-31: that first pass downsampled with a fixed vertex stride (every 13th
// of Kenai's raw 312 vertices, every 41st of Kasilof's raw 990) rather than anything
// curvature-aware. Raw vertex density isn't uniform along either river -- dense through real
// meanders, sparse on straight stretches -- so a fixed stride skips whole meander loops and
// chords straight across them, visibly cutting corners on the rendered map line. Re-simplified
// from the same raw KPB paths (Kenai: path index 2 of its 11 disconnected mapped reaches, 312
// raw vertices; Kasilof: its single 990-vertex path) via Douglas-Peucker at a 50m tolerance,
// in the same local flat-meters projection nearestSegment already uses below (lat*111320,
// lng*111320*cos(refLat)). Result: Kenai 159 points (49.0m actual max deviation from the raw
// line), Kasilof 81 points (48.7m actual max deviation). Both endpoints are the true first/
// last raw vertex, same as the stride-sampled version.
private val KENAI_RIVER_CENTERLINE = listOf(
    60.5481177 to -151.2628143,
    60.5510267 to -151.2419519,
    60.5497456 to -151.2337908,
    60.5459943 to -151.2258416,
    60.5439376 to -151.2258036,
    60.5421754 to -151.2282353,
    60.5398358 to -151.2468233,
    60.5371988 to -151.2525346,
    60.5349798 to -151.2530788,
    60.5311711 to -151.2508361,
    60.5226364 to -151.2449343,
    60.5215422 to -151.2425341,
    60.5211004 to -151.2379416,
    60.5244092 to -151.2310349,
    60.5263248 to -151.2224648,
    60.5268041 to -151.2093148,
    60.5279486 to -151.2005954,
    60.5302507 to -151.1973329,
    60.5381515 to -151.1935145,
    60.5400222 to -151.1879357,
    60.5401742 to -151.1835035,
    60.5395024 to -151.1780025,
    60.5383723 to -151.1756063,
    60.5366998 to -151.1756094,
    60.5308466 to -151.1806792,
    60.5262745 to -151.1786541,
    60.5224669 to -151.1748533,
    60.5196138 to -151.1700443,
    60.5183945 to -151.1633839,
    60.5186212 to -151.1592328,
    60.5206685 to -151.1554920,
    60.5256220 to -151.1574089,
    60.5288189 to -151.1569863,
    60.5319992 to -151.1504375,
    60.5390748 to -151.1458952,
    60.5397573 to -151.1352862,
    60.5447552 to -151.1272196,
    60.5472054 to -151.1201920,
    60.5473258 to -151.1151184,
    60.5467502 to -151.1136826,
    60.5435541 to -151.1101215,
    60.5414395 to -151.1055714,
    60.5328554 to -151.0983210,
    60.5231463 to -151.0957453,
    60.5159314 to -151.0999577,
    60.5103917 to -151.0898617,
    60.5091304 to -151.0902802,
    60.5086425 to -151.1023873,
    60.5141038 to -151.1237396,
    60.5139252 to -151.1272184,
    60.5118365 to -151.1313584,
    60.5081235 to -151.1333546,
    60.5061884 to -151.1306341,
    60.5055691 to -151.1272181,
    60.5060685 to -151.1148321,
    60.5027105 to -151.1080966,
    60.4995259 to -151.1055810,
    60.4943084 to -151.1057478,
    60.4911380 to -151.1117697,
    60.4884339 to -151.1224899,
    60.4825844 to -151.1267128,
    60.4762551 to -151.1185796,
    60.4751841 to -151.1141851,
    60.4765360 to -151.1088270,
    60.4823623 to -151.1013234,
    60.4833532 to -151.0962453,
    60.4804939 to -151.0882686,
    60.4768021 to -151.0813657,
    60.4761064 to -151.0774373,
    60.4816009 to -151.0659663,
    60.4832019 to -151.0575501,
    60.4799330 to -151.0286971,
    60.4809500 to -151.0193734,
    60.4836449 to -151.0141362,
    60.4853366 to -151.0077429,
    60.4838025 to -150.9991515,
    60.4812312 to -150.9934013,
    60.4754617 to -150.9867012,
    60.4741742 to -150.9866155,
    60.4688324 to -150.9743473,
    60.4660992 to -150.9668710,
    60.4622110 to -150.9515249,
    60.4592831 to -150.9463142,
    60.4598518 to -150.9390302,
    60.4623167 to -150.9345590,
    60.4659352 to -150.9324720,
    60.4754412 to -150.9205984,
    60.4761134 to -150.9125519,
    60.4751520 to -150.9079036,
    60.4756138 to -150.9013736,
    60.4835755 to -150.8809016,
    60.4911147 to -150.8686965,
    60.4912005 to -150.8649955,
    60.4988901 to -150.8614558,
    60.5030555 to -150.8554715,
    60.5080397 to -150.8521817,
    60.5112000 to -150.8481814,
    60.5130516 to -150.8407615,
    60.5129690 to -150.8341204,
    60.5088637 to -150.8255087,
    60.5100440 to -150.8019015,
    60.5122142 to -150.7897192,
    60.5170920 to -150.7819952,
    60.5227236 to -150.7786283,
    60.5256408 to -150.7736083,
    60.5288147 to -150.7611699,
    60.5321326 to -150.7608386,
    60.5355916 to -150.7575104,
    60.5315174 to -150.7451606,
    60.5281286 to -150.7429870,
    60.5240168 to -150.7448360,
    60.5194686 to -150.7431383,
    60.5179197 to -150.7395844,
    60.5178948 to -150.7361167,
    60.5200889 to -150.7324256,
    60.5226024 to -150.7249918,
    60.5221626 to -150.7213262,
    60.5209282 to -150.7186985,
    60.5183357 to -150.7180034,
    60.5157790 to -150.7139545,
    60.5145280 to -150.7058253,
    60.5154424 to -150.7025018,
    60.5149407 to -150.6987240,
    60.5118840 to -150.6958046,
    60.5097734 to -150.6908752,
    60.5083809 to -150.6841983,
    60.5061882 to -150.6831065,
    60.5031867 to -150.6848485,
    60.5013414 to -150.6812097,
    60.5003011 to -150.6674423,
    60.4958229 to -150.6524594,
    60.4916633 to -150.6531930,
    60.4903658 to -150.6429657,
    60.4894875 to -150.6226394,
    60.4873434 to -150.6204246,
    60.4865428 to -150.6272116,
    60.4819781 to -150.6283369,
    60.4801520 to -150.6222391,
    60.4813918 to -150.6091619,
    60.4770725 to -150.5975800,
    60.4761908 to -150.5921891,
    60.4753455 to -150.5912116,
    60.4724856 to -150.5931869,
    60.4688492 to -150.6028569,
    60.4667772 to -150.6035303,
    60.4637093 to -150.6001969,
    60.4669104 to -150.5924983,
    60.4656504 to -150.5824838,
    60.4633659 to -150.5776997,
    60.4587204 to -150.5798633,
    60.4575648 to -150.5725258,
    60.4610027 to -150.5639422,
    60.4622074 to -150.5438701,
    60.4613011 to -150.5373716,
    60.4615897 to -150.5326436,
    60.4634852 to -150.5306722,
    60.4657762 to -150.5311251,
    60.4677441 to -150.5240942,
    60.4689391 to -150.5125450
)

private val KASILOF_RIVER_CENTERLINE = listOf(
    60.3856162 to -151.3001040,
    60.3851859 to -151.2913394,
    60.3837797 to -151.2883267,
    60.3821861 to -151.2882356,
    60.3799965 to -151.2914022,
    60.3794018 to -151.2976905,
    60.3765710 to -151.3033092,
    60.3737054 to -151.3038826,
    60.3685425 to -151.3020216,
    60.3673387 to -151.2996990,
    60.3673045 to -151.2963377,
    60.3687538 to -151.2944009,
    60.3720499 to -151.2938583,
    60.3724535 to -151.2892489,
    60.3715547 to -151.2873048,
    60.3691891 to -151.2864249,
    60.3644843 to -151.2909202,
    60.3617730 to -151.2909435,
    60.3594638 to -151.2829959,
    60.3581506 to -151.2818272,
    60.3522034 to -151.2837348,
    60.3470668 to -151.2881295,
    60.3454012 to -151.2875282,
    60.3425153 to -151.2890662,
    60.3365081 to -151.2829980,
    60.3348942 to -151.2833157,
    60.3324212 to -151.2883375,
    60.3267265 to -151.2898686,
    60.3226406 to -151.2809922,
    60.3220638 to -151.2748857,
    60.3211236 to -151.2725453,
    60.3179949 to -151.2697734,
    60.3166063 to -151.2556281,
    60.3168652 to -151.2501267,
    60.3162251 to -151.2484025,
    60.3134417 to -151.2523978,
    60.3128060 to -151.2514124,
    60.3126847 to -151.2452379,
    60.3094959 to -151.2479304,
    60.3072272 to -151.2445573,
    60.3038368 to -151.2469933,
    60.3029891 to -151.2443785,
    60.3038916 to -151.2415480,
    60.3064392 to -151.2416016,
    60.3093899 to -151.2355091,
    60.3089641 to -151.2330584,
    60.3080636 to -151.2323268,
    60.3049277 to -151.2345829,
    60.3039056 to -151.2325481,
    60.3034006 to -151.2272857,
    60.3047964 to -151.2228020,
    60.3085008 to -151.2228231,
    60.3093286 to -151.2196631,
    60.3084746 to -151.2165346,
    60.3044559 to -151.2159139,
    60.3017636 to -151.2136586,
    60.2994874 to -151.2137628,
    60.2967597 to -151.2163678,
    60.2927757 to -151.2140217,
    60.2911514 to -151.2154356,
    60.2872581 to -151.2238887,
    60.2864355 to -151.2240928,
    60.2854526 to -151.2218078,
    60.2862187 to -151.2151363,
    60.2846268 to -151.2133736,
    60.2827138 to -151.2082465,
    60.2771431 to -151.2193120,
    60.2750534 to -151.2078682,
    60.2753086 to -151.2057802,
    60.2736680 to -151.2016263,
    60.2701572 to -151.1995963,
    60.2661146 to -151.1914725,
    60.2614142 to -151.1912722,
    60.2602527 to -151.1894940,
    60.2595717 to -151.1853944,
    60.2557543 to -151.1815377,
    60.2558728 to -151.1716172,
    60.2494891 to -151.1729862,
    60.2464218 to -151.1765360,
    60.2406290 to -151.1699975,
    60.2329842 to -151.1653133
)

// Upriver beluga limit -- feeds both isWithinWellSourcedWater's buffer check (see
// realLinesForZone below) and riverCenterlinesForZoneSlug's map rendering. Truncates a copy of
// each full centerline above, walking cumulative distance from the mouth (index 0) in the same
// local flat-meters projection the rest of this file uses, and interpolating the exact cut
// vertex rather than snapping to the nearest existing point.
//
// Kenai: 11.5 mi. Corroborated three ways -- field observation; the pre-KPB-swap geometry's own
// "11 mile apex" (see KENAI.fullRing's spike comment above); and the KPB water-body layer
// itself, which shows a single large island (~59-91 acres) at mile 9.3-10.7 and then the
// channel braiding into a chain of separate smaller islands starting at mile 11.63 and
// continuing at least to mile 17.4 -- real habitat complexity picks up right at this cutoff.
//
// Kasilof: 7.5 mi, just above the Sterling Highway bridge (KPB_Bridges_view's "KASILOF RIVER
// BRIDGE", mile 7.39). Rests on field observation alone -- KPB's water-body layer shows zero
// islands anywhere on the Kasilof (no interior rings across its full ~18.2mi mapped length),
// and its 5ft-contour layer has no coverage past mile ~7, so unlike the Kenai this number has
// no independent corroborating landmark. Treat it as the less-certain of the two.
private const val KENAI_RIVER_BELUGA_LIMIT_MILES = 11.5
private const val KASILOF_RIVER_BELUGA_LIMIT_MILES = 7.5
private const val METERS_PER_MILE = 1609.344

private fun truncateAtRiverMiles(
    centerline: List<Pair<Double, Double>>,
    miles: Double
): List<Pair<Double, Double>> {
    if (centerline.size < 2) return centerline
    val targetMeters = miles * METERS_PER_MILE

    val result = mutableListOf(centerline[0])
    var cumMeters = 0.0
    for (i in 0 until centerline.size - 1) {
        val (lat1, lng1) = centerline[i]
        val (lat2, lng2) = centerline[i + 1]
        val mPerLng = metersPerDegreeLng((lat1 + lat2) / 2.0)
        val dx = (lng2 - lng1) * mPerLng
        val dy = (lat2 - lat1) * METERS_PER_DEGREE_LAT
        val segMeters = sqrt(dx * dx + dy * dy)

        if (cumMeters + segMeters >= targetMeters) {
            val t = if (segMeters > 0.0) (targetMeters - cumMeters) / segMeters else 0.0
            result.add((lat1 + t * (lat2 - lat1)) to (lng1 + t * (lng2 - lng1)))
            return result
        }

        cumMeters += segMeters
        result.add(centerline[i + 1])
    }
    return result // centerline is shorter than the requested cut -- return it whole
}

private val KENAI_RIVER_CENTERLINE_BELUGA_LIMIT =
    truncateAtRiverMiles(KENAI_RIVER_CENTERLINE, KENAI_RIVER_BELUGA_LIMIT_MILES)
private val KASILOF_RIVER_CENTERLINE_BELUGA_LIMIT =
    truncateAtRiverMiles(KASILOF_RIVER_CENTERLINE, KASILOF_RIVER_BELUGA_LIMIT_MILES)

// Every real linestring (coastline +, for kenai, its two river centerlines) a given
// well-sourced zone can be measured against for the buffer-distance fallback below. Uses the
// beluga-limit-truncated centerlines, not the full ones -- this feeds isWithinWellSourcedWater's
// validation check, where upstream-of-the-limit points should NOT read as near-river.
private fun realLinesForZone(zone: CoastlineZone): List<List<Pair<Double, Double>>> {
    val lines = mutableListOf(zone.coastlineOnly)
    if (zone.slug == "kenai") {
        lines.add(KENAI_RIVER_CENTERLINE_BELUGA_LIMIT)
        lines.add(KASILOF_RIVER_CENTERLINE_BELUGA_LIMIT)
    }
    return lines
}

/**
 * Public accessor for a watched zone's river centerlines, for rendering (see
 * SightingsMapScreen's "Belugas present" river shading). A zone's polygon boundary traces each
 * river as a there-and-back spike (see KENAI's doc comment above) so its interior/outline can
 * never actually render as a colored river on a map -- these clean, single-direction centerlines
 * are what a river-specific LineLayer should draw instead. Empty for any zone without one.
 *
 * Beluga-limit-truncated, not the full KPB-mapped centerlines -- this LineLayer is drawn in the
 * zone's live status color at full width/opacity (see SightingsMapScreen), so it reads as "belugas
 * plausibly here," and must stop exactly where the shading fill it sits on top of stops. The full
 * centerlines used to be returned here; that let the stroke run the river's entire mapped length
 * (50mi/18mi) well past the buffer's 11.5mi/7.5mi cutoff, coloring the whole river regardless of
 * the limit.
 */
fun riverCenterlinesForZoneSlug(slug: String): List<List<Pair<Double, Double>>> = when (slug) {
    "kenai" -> listOf(KENAI_RIVER_CENTERLINE_BELUGA_LIMIT, KASILOF_RIVER_CENTERLINE_BELUGA_LIMIT)
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

// ============================================================================================
// Whale-position redesign additions (observer-position -> whale-position, see
// supabase/migrations/20260901000000_add_whale_position_columns.sql). isWithinWellSourcedWater
// above stays exactly as it was -- still used by GeofenceUtils.isWithin3DFunnel/
// sectorEndpointWithinGeofence, which are still real, active checks (the CAMERA flow's
// heading/distance dialog still previews whether a real projection lands on water). Everything
// below is new, sibling logic for validating/deriving a WHALE position instead of an observer's.
// ============================================================================================

/**
 * Same three-tier structure as [isWithinWellSourcedWater] (zone containment -> buffer-distance
 * to real coastline/river data -> confident-false within reach / null out of reach), but
 * buffer-driven rather than altitude-driven -- there's no observer altitude to reason about once
 * the point being validated is the animal's own estimated position, not an observer's sightline.
 * [bufferMeters] is the caller's responsibility to choose sensibly: see
 * GeofenceUtils.isWhalePositionVerified, which caps it before calling here so a user-chosen
 * uncertainty radius can't be inflated to make verification easier ("I'm very unsure" must not
 * become "verified" just because the buffer got wider along with it).
 */
fun isWithinGeofenceBuffer(lat: Double, lng: Double, bufferMeters: Double): Boolean? {
    val bufferKm = bufferMeters / 1000.0
    var withinReachOfAnyZone = false

    for (zone in WELL_SOURCED_ZONES) {
        if (pointInPolygon(lat, lng, zone.fullRing)) return true

        var nearestKm = Double.MAX_VALUE
        for (line in realLinesForZone(zone)) {
            val segment = nearestSegment(lat, lng, line) ?: continue
            val distKm = segment.distanceMeters / 1000.0
            if (distKm < nearestKm) nearestKm = distKm
        }

        if (nearestKm <= bufferKm) return true
        if (nearestKm <= ZONE_AUTHORITATIVE_REACH_KM) withinReachOfAnyZone = true
    }

    return if (withinReachOfAnyZone) false else null
}

// Fallback search cap: 300m, not DistanceBucket.CLOSE's 150m -- the Kenai's tidal flats are
// wide enough that the true waterline can sit further out than CLOSE assumes, and a fallback
// guess is as likely to undershoot as overshoot (field observation, not a measurement).
// FALLBACK_UNCERTAINTY_RADIUS_METERS is deliberately larger than this cap, not equal to it --
// the cap only bounds how far the search looks for water; keeping the recorded uncertainty
// wider than the search itself is what keeps this honest. The number should communicate "we
// guessed", not "we're confident to within 300m".
private const val FALLBACK_SEARCH_CAP_METERS = 300.0
private const val FALLBACK_SEARCH_STEP_METERS = 25.0
const val FALLBACK_UNCERTAINTY_RADIUS_METERS = 500.0

/**
 * NOT CALLED FROM LoggingScreen ANYMORE (BUG FIX item 46) -- a real submitted sighting
 * (position_source=FALLBACK, no heading/distance) proved this function's own [isWithinWellSourcedWater]
 * "is this point in water" check can be wrong on a complex coastline, so LoggingScreen now always
 * shows its "Can't Place This Sighting" dialog when no heading was given, unconditionally, rather
 * than trying this guess first. Left defined (not deleted) in case its accuracy is ever improved
 * enough to reconsider offering it back as an explicit, user-confirmed guess rather than a silent
 * save -- do not wire this back into LoggingScreen's submit path without addressing that first.
 *
 * Last-resort whale-position guess for LoggingScreen, used only when the observer gave no
 * heading/distance reading at all (SightingRecord.positionSource = FALLBACK). Walks outward
 * from the observer along [computeDefaultOffshoreHeadingDegrees]'s perpendicular-to-nearest-
 * coastline bearing -- the same bearing the map's old sector fallback used, reused rather than
 * writing new geometry -- in [FALLBACK_SEARCH_STEP_METERS] steps up to
 * [FALLBACK_SEARCH_CAP_METERS], returning the furthest point still confirmed in real water. On
 * a narrow river this stops at the far bank instead of overshooting into wetlands beyond; on
 * wide tidal flats it stays close in rather than guessing far, since a beluga sighting is as
 * likely to be near as far.
 *
 * Returns null in two cases a caller must treat identically -- route to the existing geofence-
 * warning/SAVE-ANYWAY dialog rather than fabricate a position: no well-sourced zone is within
 * reach of the observer at all ([isWithinWellSourcedWater] returns null there -- no real
 * geometry to walk against), or the walk never confirmed a single step in water within the cap
 * (plausible on tidal flats wide enough that the true waterline sits past 300m).
 */
fun projectOffshoreFallback(observerLat: Double, observerLng: Double): Pair<Double, Double>? {
    // isWithinWellSourcedWater's own null case (out of reach of every well-sourced zone) is
    // reused here as "is there real coastline geometry near the observer to walk against" --
    // altitude is irrelevant to that reach question, so 0.0 is a no-op argument, not a claim.
    if (isWithinWellSourcedWater(observerLat, observerLng, 0.0) == null) return null

    val bearing = computeDefaultOffshoreHeadingDegrees(observerLat, observerLng)
    var lastConfirmedWater: Pair<Double, Double>? = null
    var dist = FALLBACK_SEARCH_STEP_METERS
    while (dist <= FALLBACK_SEARCH_CAP_METERS) {
        val candidate = destinationPoint(observerLat, observerLng, bearing, dist)
        val inWater = isWithinWellSourcedWater(candidate.first, candidate.second, 0.0) == true
        if (inWater) {
            lastConfirmedWater = candidate
        } else if (lastConfirmedWater != null) {
            // Was in water, just stepped out of it (e.g. the far bank of a narrow river) --
            // stop here instead of continuing past the water into wetlands beyond.
            break
        }
        dist += FALLBACK_SEARCH_STEP_METERS
    }
    return lastConfirmedWater
}
