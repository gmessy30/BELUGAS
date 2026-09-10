package com.cookinlet.belugas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
import org.maplibre.compose.camera.*
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.GeoJsonOptions
import org.maplibre.spatialk.geojson.Point
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.*
import org.maplibre.compose.expressions.value.StringValue
import org.maplibre.compose.expressions.value.EquatableValue
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.*
import androidx.compose.foundation.shape.CircleShape
import org.maplibre.compose.util.ClickResult
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightingsMapScreen(
    localSightings: List<LocalPendingSighting>,
    remoteSightings: List<SightingRecord>,
    isLoading: Boolean,
    region: RegionConfig = Regions.COOK_INLET,
    currentAltitude: Double,
    // "Belugas present" river shading -- shown to everyone unconditionally (no subscription
    // or proximity gate, unlike the bottom banner in App.kt), same RED/YELLOW/BLUE model.
    // Defaulted empty so nothing renders until App.kt's hoisted fetches land.
    watchedZoneShadingAreas: List<WatchedZoneShadingRecord> = emptyList(),
    watchedZoneStatuses: List<WatchedZoneSightingStatus> = emptyList(),
    // Whether get_watched_zone_statuses has EVER succeeded this app run -- distinct from
    // watchedZoneStatuses being merely empty (which, once this is true, is a real "confirmed no
    // data" answer, not "we haven't checked"). See the shading loop below: a zone whose status
    // resolves to UNKNOWN (Kenai via kenaiBelugaStatus, or any other zone via this flag) isn't
    // drawn in a placeholder color -- it isn't drawn at all.
    hasEverFetchedWatchedZoneStatuses: Boolean = false,
    // Kenai's real tide-cycle-aware status (App.kt's kenaiBelugaStatus, derived from
    // get_kenai_presence_state) -- used for the Kenai zone's shading color INSTEAD OF
    // watchedZoneStatuses' flat-decay computation below, so the map and the bottom banner can
    // never show Kenai in disagreeing colors. Every other watched zone still uses the flat-decay
    // path unchanged -- this only overrides the one zone that has a real predictor. UNKNOWN
    // (App.kt's default before any successful Kenai poll) flows through the same way.
    kenaiBelugaStatus: BelugaPresenceStatus = BelugaPresenceStatus.UNKNOWN,
    onCloseMap: () -> Unit,
    onRefreshRemote: () -> Unit = {}
) {
    // Landing here directly (deep nav, or as the configured launch screen) previously never
    // triggered a fetch at all, so the map could show a stale in-memory snapshot indefinitely
    // until some unrelated action happened to refresh it. Firing on every entry into this
    // screen's composition guarantees a fresh fetch regardless of how we got here.
    LaunchedEffect(Unit) {
        onRefreshRemote()
    }

    // "High confidence only" -- photo_url is not null OR observer_tier in (1,2), hiding plain
    // manual tier-3 reports. Applied once here, before any of the three places downstream that
    // read remoteSightings (this combine step, circleGeoJsonString, travelStubGeoJsonString),
    // rather than three separate filters that could drift -- an uncertainty circle or travel
    // stub with no matching pin (or vice versa) would be a confusing half-filtered map.
    // localSightings (this device's own not-yet-synced queue) is deliberately never filtered --
    // see isHighConfidence's own comment on why.
    var showHighConfidenceOnly by remember { mutableStateOf(false) }
    val filteredRemoteSightings = remember(remoteSightings, showHighConfidenceOnly) {
        if (showHighConfidenceOnly) remoteSightings.filter { it.isHighConfidence } else remoteSightings
    }

    // 1. Unified Sighting Source for playback logic
    val allSightings = remember(localSightings, filteredRemoteSightings) {
        val combined = mutableListOf<SightingDisplayModel>()

        // A locally-queued sighting not yet synced to Supabase (Flag C: this used to read the
        // dead sightingEntity table, which nothing ever wrote to, so this branch was always a
        // no-op in practice). Same whaleLat/whaleLng-null skip as the remote branch below, and
        // the same real uncertainty/travel-bearing fields a synced sighting has -- there's
        // nothing structurally missing here now, unlike the old dead-table path.
        localSightings.forEach { pending ->
            val s = pending.record
            val lat = s.whaleLat
            val lng = s.whaleLng
            if (lat == null || lng == null) return@forEach
            combined.add(SightingDisplayModel(
                lat = lat, lng = lng, timestamp = s.observedAtEpochMs ?: 0L,
                total = s.countWhites + s.countGreys + s.countCalves + s.countUnknown,
                isLocal = true,
                uncertaintyRadiusMeters = s.uncertaintyRadiusMeters,
                travelBearingDegrees = s.travelBearingDegrees
            ))
        }

        // A remote sighting with no whale position -- either bad data, or a legacy row from
        // before the whale-position redesign (position_source null, only the old observer-
        // position lat/lng populated) -- has nowhere new-format to place a pin. Skip it here
        // rather than render it under the wrong meaning; it still shows up in the sightings
        // list (which doesn't need a location) via remoteSightings directly.
        filteredRemoteSightings.forEach { s ->
            val lat = s.whaleLat
            val lng = s.whaleLng
            if (lat == null || lng == null) return@forEach
            combined.add(SightingDisplayModel(
                lat = lat, lng = lng, timestamp = s.observedAtEpochMs ?: 0L,
                total = s.countWhites + s.countGreys + s.countCalves + s.countUnknown,
                isLocal = false,
                uncertaintyRadiusMeters = s.uncertaintyRadiusMeters,
                travelBearingDegrees = s.travelBearingDegrees
            ))
        }
        combined.sortedBy { it.timestamp }
    }

    // 2. Playback State Sanitization — only sightings with a real (positive) timestamp
    // define the timeline. A missing observedAtEpochMs previously defaulted to 0L, which
    // dragged minTime down to the Unix epoch (Dec 31 1969 in negative-UTC-offset zones like
    // Alaska) and forced the slider to span decades of empty data.
    val validTimestamps = remember(allSightings) {
        allSightings.map { it.timestamp }.filter { it > 0L }
    }
    val minTime = remember(validTimestamps) {
        validTimestamps.minOrNull() ?: (currentTimeMillis() - 30L * 24 * 60 * 60 * 1000L)
    }
    val maxTime = remember(validTimestamps, minTime) {
        val last = validTimestamps.maxOrNull() ?: currentTimeMillis()
        if (last <= minTime) minTime + 1000L else last
    }

    var playbackTimeMs by remember(allSightings) { mutableLongStateOf(maxTime) }
    var isPlaying by remember { mutableStateOf(false) }

    // Playback visibility toggle (Default: OFF)
    var isPlaybackVisible by remember { mutableStateOf(false) }
    var isPlaybackMinimized by remember { mutableStateOf(false) }

    // Auto-collapse to the minimized (header + play/pause + slider only) layout while
    // playing, since the full panel eats a lot of the map at ~60% of screen height — and
    // expand back out on pause/stop. Keyed only on isPlaying, so it fires once per
    // play/pause transition rather than fighting a manual toggle tap made mid-playback: a
    // user can still re-expand while playing (or re-collapse while paused) via the existing
    // header button, and that choice sticks until the next transition.
    LaunchedEffect(isPlaying) {
        isPlaybackMinimized = isPlaying
    }

    // Scrubbable range within [minTime, maxTime]. Defaults to All Time but is narrowed by
    // the quick-range shortcuts or manual date entry below.
    var selectedQuickRange by remember { mutableStateOf(QuickRange.ALL_TIME) }
    var rangeStart by remember(minTime) { mutableLongStateOf(minTime) }
    var rangeEnd by remember(maxTime) { mutableLongStateOf(maxTime) }
    var showStartDatePicker by remember { mutableStateOf(false) }
    var showEndDatePicker by remember { mutableStateOf(false) }

    fun applyQuickRange(range: QuickRange) {
        selectedQuickRange = range
        val (start, end) = range.resolve(minTime, maxTime, currentTimeMillis())
        rangeStart = start
        rangeEnd = end
        playbackTimeMs = start
        isPlaying = false
    }

    // Advanced Controls State
    val speedOptions = listOf(1, 5, 10, 30, 60)
    var speedMultiplier by remember { mutableIntStateOf(5) }

    val fadeOptionsHours = listOf(2L, 6L, 12L, 24L, Long.MAX_VALUE)
    var selectedFadeHours by remember { mutableLongStateOf(6L) }

    val fadeWindowMs = if (selectedFadeHours == Long.MAX_VALUE) {
        Long.MAX_VALUE
    } else {
        selectedFadeHours * 3600 * 1000L
    }

    // 3. Automated playback ticker
    LaunchedEffect(isPlaying, isPlaybackVisible, rangeStart, rangeEnd, speedMultiplier) {
        if (isPlaying && isPlaybackVisible) {
            val baseStepMs = 60_000L // 1 minute per tick at 1x
            val effectiveStepMs = baseStepMs * speedMultiplier

            while (isPlaying && isPlaybackVisible) {
                delay(100)
                if (playbackTimeMs >= rangeEnd) {
                    isPlaying = false
                } else {
                    playbackTimeMs = (playbackTimeMs + effectiveStepMs).coerceAtMost(rangeEnd)
                }
            }
        }
    }

    // Dynamic Camera State initialized from active RegionConfig
    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(longitude = region.defaultCenterLng, latitude = region.defaultCenterLat),
            zoom = region.defaultZoom
        )
    )
    val coroutineScope = rememberCoroutineScope()

    // Populated when a tapped cluster's sightings all share one exact coordinate (so no
    // further zoom could ever spatially separate them) -- (timestamp, title) pairs, most
    // recent first. Non-null shows the same-location sightings as a bottom sheet.
    var samePointSightings by remember { mutableStateOf<List<Pair<Long, String>>?>(null) }

    Box(modifier = Modifier.fillMaxSize()) {
        // --- 1. ONLINE MAPLIBRE VECTOR MAP ---
        // Forcing a surface re-bind ensures the GL context isn't lost on mode changes or data updates
        key(allSightings.size, isPlaybackVisible) {
            val baseMapOptions = getMapOptions()
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                cameraState = cameraState,
                // Using high-reliability CARTO Positron vector tiles
                baseStyle = BaseStyle.Uri("https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"),
                // Default ornament padding is zero on all sides, which puts the scale bar
                // (TopStart) under the system status bar and the logo/attribution
                // (BottomStart/BottomEnd) behind the "Belugas present" banner drawn on top of
                // this screen in App.kt -- push both off those obstructions. Attribution
                // visibility here is a MapLibre/OSM licensing requirement, not cosmetic.
                options = baseMapOptions.copy(
                    ornamentOptions = baseMapOptions.ornamentOptions.copy(
                        padding = PaddingValues(
                            top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                            bottom = LocalBottomContentInset.current
                        )
                    )
                )
            ) {
                // Warm up the style's font glyph cache as soon as the map mounts, instead of
                // waiting for the first real sighting label to need it. FillLayer/LineLayer
                // (the sector wedges) paint straight from in-memory geometry, but SymbolLayer
                // text requires glyph ranges fetched from the style's font server the first
                // time any text is rasterized -- previously that fetch only kicked off once
                // real sighting data produced a label, after the (also async) sightings fetch
                // completed. Mounting an invisible label immediately lets glyph loading run in
                // parallel with the sightings fetch instead of strictly after it.
                val glyphWarmupSource = rememberGeoJsonSource(
                    data = GeoJsonData.JsonString(
                        """{ "type": "FeatureCollection", "features": [ { "type": "Feature", "geometry": { "type": "Point", "coordinates": [${region.defaultCenterLng}, ${region.defaultCenterLat}] }, "properties": {} } ] }"""
                    )
                )
                SymbolLayer(
                    id = "glyph-warmup",
                    source = glyphWarmupSource,
                    textField = format(span(const("0123456789 ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz,.·"))),
                    textOpacity = const(0f),
                    textAllowOverlap = const(true)
                )

                // "Belugas present" shading -- one FillLayer+LineLayer pair per watched zone,
                // colored by its own decayed status. Drawn before the sighting sectors/pins
                // below so those stay on top of it. A plain const() color per zone (not a
                // data-driven feature-property expression) is deliberate: there's exactly one
                // watched zone (Kenai) as of this build, and Compose recomposition already
                // handles re-coloring on status change without needing per-feature expressions.
                //
                // shadingArea is get_watched_zone_shading_areas' server-side answer to "what
                // area does this zone's banner actually watch" -- for Kenai the real river
                // buffer + mouth semicircle (20260903040000_add_watched_zone_shading_areas.sql),
                // not the full zones.boundary (which also covers Kasilof and open inlet water,
                // and stays exactly as-is for dispatch/subscriptions/export). No zone-slug
                // branching here -- any other watched zone without its own narrower area comes
                // back as its own unmodified boundary from that same RPC, so this code doesn't
                // need to know or assume Kenai is the only one.
                //
                // The river buffer is a real, non-zero-width polygon (unlike zones.boundary's
                // zero-width spike, which MapLibre can't render a stroke for), so it fills and
                // outlines correctly on its own -- no separate river-centerline reinforcement
                // needed on top of it (that used to be drawn here via CoastlineGeometry.
                // riverCenterlinesForZoneSlug; removed as a redundant, weaker-looking second
                // signal for the same "watched" status this shading already communicates).
                // riverCenterlinesForZoneSlug itself is untouched -- its own doc comment already
                // marks it as a rendering-only accessor, distinct from realLinesForZone, which is
                // what the actual containment/geofence buffer check reads.
                watchedZoneShadingAreas.forEach { zoneShading ->
                    // Kenai reads the real predictor status (see this parameter's own comment);
                    // every other zone keeps the original flat-decay computation once a
                    // successful fetch has ever landed for it, UNKNOWN before that.
                    val zoneStatus = if (zoneShading.zoneSlug == "kenai") {
                        kenaiBelugaStatus
                    } else if (!hasEverFetchedWatchedZoneStatuses) {
                        BelugaPresenceStatus.UNKNOWN
                    } else {
                        computeBelugaPresenceStatus(
                            watchedZoneStatuses.find { it.zoneId == zoneShading.zoneId },
                            currentTimeMillis()
                        )
                    }
                    // UNKNOWN isn't drawn in a placeholder color -- it isn't drawn at all. A
                    // zone with no confirmed status yet gets no shading claim on the map, same
                    // as before watchedZoneShadingAreas itself has loaded (already a normal,
                    // accepted transient state in this screen) -- not a gray blob that would
                    // need its own explanation.
                    if (zoneStatus == BelugaPresenceStatus.UNKNOWN) return@forEach
                    val zoneColor = colorForBelugaPresenceStatus(zoneStatus)
                    val zoneSource = rememberGeoJsonSource(
                        data = GeoJsonData.JsonString(buildZoneShadingFeatureCollectionGeoJson(zoneShading.shadingArea))
                    )
                    FillLayer(
                        id = "watched-zone-${zoneShading.zoneSlug}-fill",
                        source = zoneSource,
                        color = const(zoneColor),
                        opacity = const(0.35f)
                    )
                    LineLayer(
                        id = "watched-zone-${zoneShading.zoneSlug}-outline",
                        source = zoneSource,
                        color = const(zoneColor),
                        width = const(3.dp),
                        opacity = const(0.7f)
                    )

                    // River-centerline reinforcement removed (visual only, per request): Kenai
                    // and Kasilof were the only two rivers on the map drawn any differently from
                    // the base style, and a second, weaker signal for the same "watched" status
                    // the shading fill above already communicates read as "these are marked for
                    // some reason" rather than "river". riverCenterlinesForZoneSlug's underlying
                    // geometry (CoastlineGeometry.kt) is untouched -- containment/geofence logic
                    // (isWithinWellSourcedWater's buffer check) reads the same source values
                    // through the separate realLinesForZone function, not through this rendering
                    // call, so nothing downstream of that geometry is affected.
                }

                // Filter logic based on current mode
                val visibleSightings = if (!isPlaybackVisible) {
                    // Standard Mode: Show everything
                    allSightings.filter { region.containsLocation(it.lat, it.lng) }
                } else {
                    // Playback Mode: Filter by time and fade window
                    allSightings.filter { s ->
                        val age = playbackTimeMs - s.timestamp
                        val isWithinFade = if (fadeWindowMs == Long.MAX_VALUE) true else (age in 0..fadeWindowMs)
                        s.timestamp <= playbackTimeMs && isWithinFade && region.containsLocation(s.lat, s.lng) 
                    }
                }

                // Uncertainty circles — always shown in standard mode regardless of the
                // playback fade timeline (a separate, independent estimate of "somewhere within
                // this radius", not another point-in-time marker). Replaces the old heading/
                // distance sector wedge: a wedge's apex reveals where the observer stood, which
                // this design specifically avoids storing at all -- a plain circle centered on
                // the (already anonymous) estimated whale position has no such tell.
                val circleGeoJsonString = remember(filteredRemoteSightings, region) {
                    val features = filteredRemoteSightings.mapNotNull { s ->
                        // No whale position -- either bad data or a legacy pre-redesign row
                        // (position_source null) -- nowhere new-format to draw a circle around.
                        val lat = s.whaleLat ?: return@mapNotNull null
                        val lng = s.whaleLng ?: return@mapNotNull null
                        val radiusMeters = s.uncertaintyRadiusMeters ?: return@mapNotNull null
                        if (!region.containsLocation(lat, lng)) return@mapNotNull null
                        buildCircleGeoJsonFeature(lat, lng, radiusMeters)
                    }
                    """{ "type": "FeatureCollection", "features": [ ${features.joinToString(",")} ] }"""
                }
                val circleSource = rememberGeoJsonSource(data = GeoJsonData.JsonString(circleGeoJsonString))

                // Fill + outline, drawn before the point markers below so circles sit underneath them.
                FillLayer(
                    id = "sighting-uncertainty-fill",
                    source = circleSource,
                    color = const(Color(0xFF00E5FF)),
                    opacity = const(0.18f)
                )
                LineLayer(
                    id = "sighting-uncertainty-outline",
                    source = circleSource,
                    color = const(Color(0xFF00E5FF)),
                    width = const(1.5.dp)
                )

                // Travel-direction stubs — drawn only where a sighting has a recorded
                // travelBearingDegrees (optional; most won't). Snapped to the nearest of 8
                // compass points for display, per that function's own comment. Plain line, no
                // arrowhead, by design (see buildTravelStubGeoJsonFeature's own comment) -- reads
                // as a handle on the sighting's own dot marker, not a second glyph.
                val travelStubGeoJsonString = remember(filteredRemoteSightings, region) {
                    val features = filteredRemoteSightings.mapNotNull { s ->
                        val lat = s.whaleLat ?: return@mapNotNull null
                        val lng = s.whaleLng ?: return@mapNotNull null
                        val bearing = s.travelBearingDegrees ?: return@mapNotNull null
                        if (!region.containsLocation(lat, lng)) return@mapNotNull null
                        buildTravelStubGeoJsonFeature(lat, lng, snapToNearestCompass8Degrees(bearing))
                    }
                    """{ "type": "FeatureCollection", "features": [ ${features.joinToString(",")} ] }"""
                }
                val travelStubSource = rememberGeoJsonSource(data = GeoJsonData.JsonString(travelStubGeoJsonString))
                LineLayer(
                    id = "sighting-travel-stubs",
                    source = travelStubSource,
                    color = const(Color.White),
                    width = const(2.dp)
                )

                // Safe GeoJSON source initialization without LinkedHashMap serialization errors
                val geoJsonString = remember(visibleSightings, playbackTimeMs, fadeWindowMs, isPlaybackVisible) {
                    val features = visibleSightings.map { s ->
                        val alpha: Float
                        val captionText: String

                        // "⏳ QUEUED · " prefix is the label half of this pin's pending
                        // treatment -- see the color switch() on sightings-circles/-labels below
                        // for the other half. Distinct on both axes (not just alpha) so it reads
                        // as "not yet confirmed" at a glance, not just a fainter dot.
                        val queuedPrefix = if (s.isLocal) "⏳ QUEUED · " else ""

                        if (!isPlaybackVisible) {
                            alpha = 1.0f
                            captionText = "$queuedPrefix${s.total} Belugas · ${formatDateLabel(s.timestamp)}"
                        } else {
                            val age = playbackTimeMs - s.timestamp
                            // Calculate opacity based on age
                            alpha = if (fadeWindowMs == Long.MAX_VALUE || age <= 0) 1.0f
                                    else (1.0f - (age.toFloat() / fadeWindowMs.toFloat())).coerceIn(0.2f, 1.0f)

                            val dateLabel = formatDateLabel(s.timestamp)
                            val freshness = if (alpha > 0.7f) "🔴" else "⭕"
                            captionText = "$queuedPrefix$freshness ${s.total} Beluga${if (s.total != 1) "s" else ""} · $dateLabel"
                        }

                        """
                        {
                          "type": "Feature",
                          "geometry": { "type": "Point", "coordinates": [${s.lng}, ${s.lat}] },
                          "properties": {
                            "title": "$captionText",
                            "alpha": $alpha,
                            "source": "${if (s.isLocal) "Local" else "Supabase"}",
                            "sortKey": ${s.timestamp}
                          }
                        }
                        """.trimIndent()
                    }
                    """{ "type": "FeatureCollection", "features": [ ${features.joinToString(",")} ] }"""
                }

                // Clustering groups points by screen-space (pixel) proximity, not ground
                // distance, and is recomputed per zoom level -- so sightings that visually
                // overlap at the current zoom collapse into one badge, then separate back into
                // individual markers as the user zooms in past clusterMaxZoom. clusterRadius is
                // left at its default (50px) to match MapLibre's usual clustering feel.
                val source = rememberGeoJsonSource(
                    data = GeoJsonData.JsonString(geoJsonString),
                    options = GeoJsonOptions(cluster = true)
                )
                val isUnclustered = feature.has("point_count").cast<EquatableValue>()
                    .eq(const(false).cast<EquatableValue>())
                val isCluster = feature.has("point_count")

                // Tapping a cluster badge normally zooms/animates the camera in to exactly the
                // zoom level at which MapLibre's clustering would split it back into its
                // individual sightings (native getClusterExpansionZoom). But when every
                // sighting in the cluster shares the exact same coordinates -- the predominant
                // case in practice, since manual reports are almost always logged from one of a
                // handful of fixed vantage points -- no amount of zooming will ever visually
                // separate them. Detect that directly, by pulling the cluster's actual leaf
                // sightings and checking whether they all land on one point, rather than
                // inferring it indirectly from the expansion zoom number; if so, list them
                // instead of animating the camera nowhere useful.
                fun onClusterClick(clickedFeatures: List<org.maplibre.spatialk.geojson.Feature<out org.maplibre.spatialk.geojson.Geometry, kotlinx.serialization.json.JsonObject?>>): ClickResult {
                    val clusterFeature = clickedFeatures.firstOrNull() ?: return ClickResult.Pass
                    val clusterPoint = clusterFeature.geometry as? Point ?: return ClickResult.Pass

                    val leaves = source.getClusterLeaves(clusterFeature, limit = Long.MAX_VALUE, offset = 0L).features
                    val distinctPositions = leaves.mapNotNull { (it.geometry as? Point)?.coordinates }.distinct()

                    if (distinctPositions.size <= 1) {
                        samePointSightings = leaves.mapNotNull { leaf ->
                            val properties = leaf.properties ?: return@mapNotNull null
                            val title = (properties["title"] as? JsonPrimitive)?.content ?: return@mapNotNull null
                            val sortKey = (properties["sortKey"] as? JsonPrimitive)?.content?.toLongOrNull() ?: 0L
                            sortKey to title
                        }.sortedByDescending { it.first }
                        return ClickResult.Consume
                    }

                    val expansionZoom = source.getClusterExpansionZoom(clusterFeature)
                    coroutineScope.launch {
                        cameraState.animateTo(
                            CameraPosition(target = clusterPoint.coordinates, zoom = expansionZoom)
                        )
                    }
                    return ClickResult.Consume
                }

                // Circle Layer as a reliable fallback (always visible). Filtered to unclustered
                // points only -- clustered points are represented by the badge layers below
                // instead of stacking individual dots on top of each other.
                //
                // "source" ("Local" vs "Supabase") used to be set into every feature's
                // properties but never actually READ by any paint property here -- every dot
                // rendered identical bright yellow regardless of sync state, so a queued
                // sighting's pin was indistinguishable from a confirmed one even before Flag C's
                // dead-table bug meant it never showed up at all. Gray, not a shade of yellow, so
                // it reads as "not yet confirmed" rather than a fainter version of the real thing
                // -- paired with the "⏳ QUEUED ·" label prefix above, not relying on color alone.
                CircleLayer(
                    id = "sightings-circles",
                    source = source,
                    filter = isUnclustered,
                    color = switch(
                        input = feature.get("source").cast<StringValue>(),
                        case(label = "Local", output = const(Color(0xFF9E9E9E))),
                        fallback = const(Color.Yellow)
                    ),
                    radius = const(10.dp),
                    strokeColor = const(Color.Black),
                    strokeWidth = const(2.dp),
                    opacity = feature.get("alpha").cast()
                )

                // Label Layer for text. textAllowOverlap disables MapLibre's default label
                // collision detection, which otherwise silently hides all but one label when
                // multiple sightings share the same (or very close) coordinates. sortKey
                // (by timestamp) makes the resulting draw order deterministic -- the most
                // recent sighting's label paints on top -- instead of an arbitrary tie-break.
                SymbolLayer(
                    id = "sightings-labels",
                    source = source,
                    filter = isUnclustered,
                    sortKey = feature.get("sortKey").cast(),
                    textField = format(span(feature.get("title").cast<StringValue>())),
                    textColor = const(Color.Black),
                    textHaloColor = const(Color.White),
                    textHaloWidth = const(2.dp),
                    textSize = const(11.sp),
                    textOffset = offset(0.em, 2.em),
                    textOpacity = feature.get("alpha").cast(),
                    textAllowOverlap = const(true)
                )

                // Cluster badge: replaces the individual dots/labels above once enough
                // sightings land close enough together on screen. Color is distinct from the
                // plain yellow sighting dot so a badge always reads as "N sightings here",
                // never as a single sighting.
                CircleLayer(
                    id = "sightings-cluster-circles",
                    source = source,
                    filter = isCluster,
                    color = const(Color(0xFFFF6D00)),
                    radius = const(14.dp),
                    strokeColor = const(Color.Black),
                    strokeWidth = const(2.dp),
                    onClick = ::onClusterClick
                )
                SymbolLayer(
                    id = "sightings-cluster-count",
                    source = source,
                    filter = isCluster,
                    textField = format(span(feature.get("point_count_abbreviated").cast<StringValue>())),
                    textColor = const(Color.White),
                    textSize = const(12.sp),
                    textAllowOverlap = const(true),
                    onClick = ::onClusterClick
                )
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.3f)),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color.Yellow)
            }
        }

        // --- 2. FLOATING HUD OVERLAYS ---

        // Playback Mode Toggle FAB (Shown when playback is hidden). This screen renders its own
        // full-bleed map rather than going through AppBackground (whose Box applies
        // LocalBottomContentInset as padding automatically), so it has to clear the "Belugas
        // present" banner itself -- otherwise the banner (a sibling drawn on top of this whole
        // screen in App.kt) sits over this FAB whenever it's showing.
        if (!isPlaybackVisible) {
            FloatingActionButton(
                onClick = {
                    playbackTimeMs = rangeStart // Start from the beginning of the selected range
                    isPlaybackVisible = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(bottom = LocalBottomContentInset.current)
                    .padding(16.dp),
                containerColor = Color(0xFF1E1E1E),
                contentColor = Color.Yellow,
                shape = CircleShape
            ) {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("⏱ TIME-LAPSE", fontWeight = FontWeight.Bold, fontSize = 14.sp)
                }
            }
        }
        
        // Collapsible Advanced Time Playback HUD. Same reasoning as the FAB above -- clear the
        // banner via the real measured inset, not a guess. The panel's own expanded/minimized
        // height doesn't factor in here: it's anchored from the bottom via padding, so
        // BottomCenter alignment re-measures around whatever height the panel content currently
        // is regardless of how tall the banner's own inset is.
        AnimatedVisibility(
            visible = isPlaybackVisible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = LocalBottomContentInset.current)
                .padding(12.dp)
        ) {
            Surface(
                color = Color(0xFA181818),
                shape = RoundedCornerShape(16.dp),
                tonalElevation = 8.dp,
                shadowElevation = 8.dp
            ) {
                Column(
                    modifier = Modifier.padding(12.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    // Header Row: Active Date + Minimize + Close
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = formatDateTime(playbackTimeMs),
                            color = Color.Yellow,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            IconButton(
                                onClick = { isPlaybackMinimized = !isPlaybackMinimized },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text(
                                    if (isPlaybackMinimized) "⌃" else "⌄",
                                    color = Color.Gray,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 16.sp
                                )
                            }
                            IconButton(
                                onClick = {
                                    isPlaying = false
                                    isPlaybackVisible = false
                                },
                                modifier = Modifier.size(28.dp)
                            ) {
                                Text("✕", color = Color.Gray, fontWeight = FontWeight.Bold)
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(4.dp))

                    // Scrubber Row: Play/Pause + Slider — kept visible even when minimized so
                    // playback stays controllable while the rest of the panel is tucked away.
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = {
                                // Replaying after reaching the end otherwise silently does
                                // nothing: the ticker's very first check sees playbackTimeMs
                                // already >= rangeEnd and immediately flips isPlaying back off.
                                if (!isPlaying && playbackTimeMs >= rangeEnd) {
                                    playbackTimeMs = rangeStart
                                }
                                isPlaying = !isPlaying
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                            modifier = Modifier.height(36.dp).padding(end = 8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text(if (isPlaying) "⏸ PAUSE" else "▶ PLAY", color = Color.White, fontSize = 11.sp)
                        }

                        if (rangeEnd > rangeStart) {
                            // Slider's value/valueRange are Float -- offset to rangeStart before
                            // converting rather than feeding it absolute epoch-millis directly.
                            // Float32 has 24 bits of mantissa; current epoch-millis (~1.7e12)
                            // needs ~41 bits just for its integer part, leaving a precision floor
                            // of 2^(41-24) = 131,072ms (~2.2 min) AT THAT MAGNITUDE, regardless of
                            // how narrow [rangeStart, rangeEnd] actually is. A multi-month range
                            // dilutes that floor into thousands of positions (indistinguishable
                            // from continuous to a finger) -- but a device with only a handful of
                            // sightings from one tight observation session (minutes apart) could
                            // have rangeEnd - rangeStart small enough that the floor collapses the
                            // whole drag to a literal handful of reachable positions. Subtracting
                            // rangeStart first keeps the Float's magnitude equal to the SPAN being
                            // scrubbed (minutes to months), not the giant absolute Unix timestamp,
                            // which keeps that same 24-bit mantissa far more precise than any drag
                            // gesture needs. This looks like pointless indirection -- it isn't;
                            // removing the offset silently reintroduces a bug that only shows up
                            // on sparse/clustered data, not in a full-dataset smoke test.
                            Slider(
                                value = (playbackTimeMs.coerceIn(rangeStart, rangeEnd) - rangeStart).toFloat(),
                                onValueChange = {
                                    playbackTimeMs = rangeStart + it.toLong()
                                    isPlaying = false
                                },
                                valueRange = 0f..(rangeEnd - rangeStart).toFloat(),
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Yellow,
                                    activeTrackColor = Color.Yellow,
                                    inactiveTrackColor = Color.DarkGray
                                )
                            )
                        }
                    }

                    if (!isPlaybackMinimized) {
                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

                        // Quick-range shortcuts, so users aren't stuck scrubbing years of
                        // mostly-empty timeline to find the handful of days with real data.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            QuickRange.entries.forEach { range ->
                                FilterChip(
                                    selected = selectedQuickRange == range,
                                    onClick = {
                                        // CUSTOM has no resolved range of its own to jump to --
                                        // it's an entry point into the same FROM/TO date pickers
                                        // below, not a quick-range like the other four. Opens
                                        // FROM directly rather than making someone hunt for the
                                        // separate buttons underneath; selectedQuickRange only
                                        // actually becomes CUSTOM once a date is confirmed (see
                                        // those pickers' own onClick), same as picking a date
                                        // today already does without touching this chip at all.
                                        if (range == QuickRange.CUSTOM) {
                                            showStartDatePicker = true
                                        } else {
                                            applyQuickRange(range)
                                        }
                                    },
                                    label = { Text(range.label, fontSize = 9.sp) },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color.Yellow,
                                        selectedLabelColor = Color.Black,
                                        containerColor = Color(0xFF2A2A2A),
                                        labelColor = Color.White
                                    )
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        // Manual start/end date entry for a custom range.
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            TextButton(onClick = { showStartDatePicker = true }, modifier = Modifier.weight(1f)) {
                                Text("FROM ${formatDateLabel(rangeStart)}", fontSize = 10.sp, color = Color(0xFF00E5FF))
                            }
                            TextButton(onClick = { showEndDatePicker = true }, modifier = Modifier.weight(1f)) {
                                Text("TO ${formatDateLabel(rangeEnd)}", fontSize = 10.sp, color = Color(0xFF00E5FF))
                            }
                        }

                        HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 1.dp, modifier = Modifier.padding(vertical = 4.dp))

                        // Advanced Toggles: Speed & Fade
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Speed Multiplier Row
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("SPEED:", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
                                speedOptions.forEach { speed ->
                                    FilterChip(
                                        selected = speedMultiplier == speed,
                                        onClick = { speedMultiplier = speed },
                                        label = { Text("${speed}x", fontSize = 9.sp) },
                                        modifier = Modifier.padding(horizontal = 2.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color.Yellow,
                                            selectedLabelColor = Color.Black,
                                            containerColor = Color(0xFF2A2A2A),
                                            labelColor = Color.White
                                        )
                                    )
                                }
                            }

                            // Fade Window Row
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("FADE:", color = Color.Gray, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(end = 4.dp))
                                fadeOptionsHours.forEach { hours ->
                                    val label = if (hours == Long.MAX_VALUE) "ALL" else "${hours}h"
                                    FilterChip(
                                        selected = selectedFadeHours == hours,
                                        onClick = { selectedFadeHours = hours },
                                        label = { Text(label, fontSize = 9.sp) },
                                        modifier = Modifier.padding(horizontal = 2.dp),
                                        colors = FilterChipDefaults.filterChipColors(
                                            selectedContainerColor = Color(0xFF00E5FF),
                                            selectedLabelColor = Color.Black,
                                            containerColor = Color(0xFF2A2A2A),
                                            labelColor = Color.White
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        if (showStartDatePicker) {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = rangeStart)
            DatePickerDialog(
                onDismissRequest = { showStartDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { picked ->
                            rangeStart = picked.coerceIn(minTime, maxTime).coerceAtMost(rangeEnd - 1000L)
                            selectedQuickRange = QuickRange.CUSTOM
                            playbackTimeMs = rangeStart
                            isPlaying = false
                        }
                        showStartDatePicker = false
                    }) { Text("OK", color = Color.Yellow) }
                },
                dismissButton = {
                    TextButton(onClick = { showStartDatePicker = false }) { Text("CANCEL", color = Color.White) }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }

        if (showEndDatePicker) {
            val datePickerState = rememberDatePickerState(initialSelectedDateMillis = rangeEnd)
            DatePickerDialog(
                onDismissRequest = { showEndDatePicker = false },
                confirmButton = {
                    TextButton(onClick = {
                        datePickerState.selectedDateMillis?.let { picked ->
                            rangeEnd = picked.coerceIn(minTime, maxTime).coerceAtLeast(rangeStart + 1000L)
                            selectedQuickRange = QuickRange.CUSTOM
                            if (playbackTimeMs > rangeEnd) playbackTimeMs = rangeEnd
                            isPlaying = false
                        }
                        showEndDatePicker = false
                    }) { Text("OK", color = Color.Yellow) }
                },
                dismissButton = {
                    TextButton(onClick = { showEndDatePicker = false }) { Text("CANCEL", color = Color.White) }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }

        // Same-location sightings sheet: shown instead of a cluster-expansion zoom when the
        // tapped cluster's sightings all share one exact coordinate, so the individual reports
        // are still reachable even though the map can never spatially separate them.
        samePointSightings?.let { sightings ->
            ModalBottomSheet(onDismissRequest = { samePointSightings = null }) {
                Text(
                    "${sightings.size} sightings at this location",
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                )
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(sightings) { (_, title) ->
                        Text(title, modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp))
                        HorizontalDivider()
                    }
                }
            }
        }

        // Top Header
        Row(
            modifier = Modifier.fillMaxWidth().statusBarsPadding().padding(16.dp).align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            ObserverElevationTip(currentAltitudeMeters = currentAltitude)
            Row(verticalAlignment = Alignment.CenterVertically) {
                // "High confidence only" -- see isHighConfidence's own comment for the
                // photo_url/observer_tier rule. Hides plain manual tier-3 reports without
                // removing them from the underlying data; this device's own queued/local
                // sightings are never affected (see filteredRemoteSightings' own comment).
                Button(
                    onClick = { showHighConfidenceOnly = !showHighConfidenceOnly },
                    colors = ButtonDefaults.buttonColors(
                        containerColor = if (showHighConfidenceOnly) Color(0xFF00E5FF) else Color.Black.copy(alpha = 0.85f)
                    )
                ) {
                    Text(
                        if (showHighConfidenceOnly) "✓ VERIFIED ONLY" else "VERIFIED ONLY",
                        color = if (showHighConfidenceOnly) Color.Black else Color.White,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = onCloseMap,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.85f))
                ) {
                    Text("✕ CLOSE MAP", color = Color.White)
                }
            }
        }
    }
}

// Local model for combined map display
private data class SightingDisplayModel(
    val lat: Double,
    val lng: Double,
    val timestamp: Long,
    val total: Int,
    val isLocal: Boolean,
    // Uncertainty circle radius -- replaces the old heading/distance sector wedge. Null when a
    // sighting (local or remote) has no recorded value.
    val uncertaintyRadiusMeters: Double?,
    // The animal's own absolute travel direction, optional -- null renders as a plain dot with
    // no arrow, same as no direction having been recorded at all.
    val travelBearingDegrees: Double?
)

// Wraps a watched zone's already-GeoJSON shading geometry (see WatchedZoneShadingRecord's
// comment on how PostgREST serializes it) into a single-feature FeatureCollection MapLibre's
// GeoJsonData can consume directly.
private fun buildZoneShadingFeatureCollectionGeoJson(shadingArea: JsonElement): String {
    return buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            addJsonObject {
                put("type", "Feature")
                put("geometry", shadingArea)
                put("properties", buildJsonObject {})
            }
        }
    }.toString()
}

