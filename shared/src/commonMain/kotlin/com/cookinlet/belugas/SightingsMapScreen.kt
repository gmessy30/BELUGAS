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
import com.cookinlet.belugas.db.SightingEntity
import org.maplibre.compose.map.MaplibreMap
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
    localSightings: List<SightingEntity>,
    remoteSightings: List<SightingRecord>,
    isLoading: Boolean,
    region: RegionConfig = Regions.COOK_INLET,
    currentAltitude: Double,
    // "Belugas present" river shading -- shown to everyone unconditionally (no subscription
    // or proximity gate, unlike the bottom banner in App.kt), same RED/YELLOW/BLUE model.
    // Defaulted empty so nothing renders until App.kt's hoisted fetches land.
    watchedZoneBoundaries: List<ZoneBoundaryRecord> = emptyList(),
    watchedZoneStatuses: List<WatchedZoneSightingStatus> = emptyList(),
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

    // 1. Unified Sighting Source for playback logic
    val allSightings = remember(localSightings, remoteSightings) {
        val combined = mutableListOf<SightingDisplayModel>()
        
        localSightings.forEach { s ->
            combined.add(SightingDisplayModel(
                lat = s.lat, lng = s.lng, timestamp = s.timestamp,
                total = (s.countWhites + s.countGreys + s.countCalves + s.countUnknown).toInt(),
                isLocal = true, heading = s.heading
            ))
        }
        
        // A remote sighting with no coordinates (e.g. bad manual/test data) has nowhere to
        // place a pin -- skip it here rather than crash; it still shows up in the sightings
        // list (which doesn't need a location) via remoteSightings directly.
        remoteSightings.forEach { s ->
            val lat = s.lat
            val lng = s.lng
            if (lat == null || lng == null) return@forEach
            combined.add(SightingDisplayModel(
                lat = lat, lng = lng, timestamp = s.observedAtEpochMs ?: 0L,
                total = s.countWhites + s.countGreys + s.countCalves + s.countUnknown,
                isLocal = false, heading = s.heading ?: "NONE"
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
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                cameraState = cameraState,
                // Using high-reliability CARTO Positron vector tiles
                baseStyle = BaseStyle.Uri("https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"),
                options = getMapOptions()
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

                // "Belugas present" river shading -- one FillLayer+LineLayer pair per watched
                // zone, colored by its own decayed status. Drawn before the sighting sectors/
                // pins below so those stay on top of it. A plain const() color per zone (not a
                // data-driven feature-property expression) is deliberate: there's exactly one
                // watched zone (Kenai) as of this build, and Compose recomposition already
                // handles re-coloring on status change without needing per-feature expressions.
                watchedZoneBoundaries.forEach { zoneBoundary ->
                    val zoneStatus = watchedZoneStatuses.find { it.zoneId == zoneBoundary.id }
                    val zoneColor = colorForBelugaPresenceStatus(
                        computeBelugaPresenceStatus(zoneStatus, currentTimeMillis())
                    )
                    val zoneSource = rememberGeoJsonSource(
                        data = GeoJsonData.JsonString(buildZoneBoundaryFeatureCollectionGeoJson(zoneBoundary.boundary))
                    )
                    FillLayer(
                        id = "watched-zone-${zoneBoundary.slug}-fill",
                        source = zoneSource,
                        color = const(zoneColor),
                        opacity = const(0.35f)
                    )
                    LineLayer(
                        id = "watched-zone-${zoneBoundary.slug}-outline",
                        source = zoneSource,
                        color = const(zoneColor),
                        width = const(2.dp)
                    )
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

                // Heading/distance sectors — always shown in standard mode regardless of the
                // playback fade timeline (a separate, independent estimate of "somewhere out
                // there in this direction/range", not another point-in-time marker).
                val sectorGeoJsonString = remember(remoteSightings, region) {
                    val features = remoteSightings.mapNotNull { s ->
                        // No coordinates -- nowhere to draw a sector from, skip it (same as the
                        // pin-placement skip above for allSightings).
                        val lat = s.lat ?: return@mapNotNull null
                        val lng = s.lng ?: return@mapNotNull null
                        val radiusMeters = s.distanceRadiusMeters ?: return@mapNotNull null
                        if (!region.containsLocation(lat, lng)) return@mapNotNull null

                        // An explicit heading is trusted as-is unless it points back at land
                        // (checkable only where we have real coastline data -- see
                        // CoastlineGeometry.headingPointsAtLand). Missing or land-pointing
                        // headings default to offshore, algorithmically derived rather than
                        // manually entered, so they get MANUAL's existing (widest) confidence
                        // tier rather than a new source tier.
                        val explicitDegrees = s.headingDegrees
                        val heading = if (explicitDegrees != null && !headingPointsAtLand(lat, lng, explicitDegrees)) {
                            val headingSource = HeadingSource.entries.firstOrNull { it.name == s.headingSource }
                                ?: HeadingSource.MANUAL
                            HeadingEstimate(explicitDegrees, headingSource, s.headingAccuracyDegrees)
                        } else {
                            val offshoreDegrees = computeDefaultOffshoreHeadingDegrees(lat, lng)
                            HeadingEstimate(offshoreDegrees, HeadingSource.MANUAL)
                        }

                        buildSectorGeoJsonFeature(lat, lng, heading, radiusMeters)
                    }
                    """{ "type": "FeatureCollection", "features": [ ${features.joinToString(",")} ] }"""
                }
                val sectorSource = rememberGeoJsonSource(data = GeoJsonData.JsonString(sectorGeoJsonString))

                // Fill + outline, drawn before the point markers below so sectors sit underneath them.
                FillLayer(
                    id = "sighting-sectors-fill",
                    source = sectorSource,
                    color = const(Color(0xFF00E5FF)),
                    opacity = const(0.25f)
                )
                LineLayer(
                    id = "sighting-sectors-outline",
                    source = sectorSource,
                    color = const(Color(0xFF00E5FF)),
                    width = const(1.5.dp)
                )

                // Safe GeoJSON source initialization without LinkedHashMap serialization errors
                val geoJsonString = remember(visibleSightings, playbackTimeMs, fadeWindowMs, isPlaybackVisible) {
                    val features = visibleSightings.map { s ->
                        val alpha: Float
                        val captionText: String

                        if (!isPlaybackVisible) {
                            alpha = 1.0f
                            captionText = "${s.total} Belugas · ${formatDateLabel(s.timestamp)}"
                        } else {
                            val age = playbackTimeMs - s.timestamp
                            // Calculate opacity based on age
                            alpha = if (fadeWindowMs == Long.MAX_VALUE || age <= 0) 1.0f 
                                    else (1.0f - (age.toFloat() / fadeWindowMs.toFloat())).coerceIn(0.2f, 1.0f)
                            
                            val dateLabel = formatDateLabel(s.timestamp)
                            val freshness = if (alpha > 0.7f) "🔴" else "⭕"
                            captionText = "$freshness ${s.total} Beluga${if (s.total != 1) "s" else ""} · $dateLabel"
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
                CircleLayer(
                    id = "sightings-circles",
                    source = source,
                    filter = isUnclustered,
                    color = const(Color.Yellow),
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

        // Playback Mode Toggle FAB (Shown when playback is hidden)
        if (!isPlaybackVisible) {
            FloatingActionButton(
                onClick = {
                    playbackTimeMs = rangeStart // Start from the beginning of the selected range
                    isPlaybackVisible = true
                },
                modifier = Modifier
                    .align(Alignment.BottomEnd)
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
        
        // Collapsible Advanced Time Playback HUD
        AnimatedVisibility(
            visible = isPlaybackVisible,
            enter = slideInVertically(initialOffsetY = { it }),
            exit = slideOutVertically(targetOffsetY = { it }),
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
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
                            Slider(
                                value = playbackTimeMs.coerceIn(rangeStart, rangeEnd).toFloat(),
                                onValueChange = {
                                    playbackTimeMs = it.toLong()
                                    isPlaying = false
                                },
                                valueRange = rangeStart.toFloat()..rangeEnd.toFloat(),
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
                                    onClick = { applyQuickRange(range) },
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
            Button(
                onClick = onCloseMap,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.85f))
            ) {
                Text("✕ CLOSE MAP", color = Color.White)
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
    val heading: String
)

// Wraps a zone's already-GeoJSON boundary geometry (see ZoneBoundaryRecord's comment on how
// PostgREST serializes it) into a single-feature FeatureCollection MapLibre's GeoJsonData can
// consume directly.
private fun buildZoneBoundaryFeatureCollectionGeoJson(boundary: JsonElement): String {
    return buildJsonObject {
        put("type", "FeatureCollection")
        putJsonArray("features") {
            addJsonObject {
                put("type", "Feature")
                put("geometry", boundary)
                put("properties", buildJsonObject {})
            }
        }
    }.toString()
}
