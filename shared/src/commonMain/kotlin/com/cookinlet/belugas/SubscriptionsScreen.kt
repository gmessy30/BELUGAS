package com.cookinlet.belugas

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch
import org.maplibre.compose.camera.CameraPosition
import org.maplibre.compose.camera.rememberCameraState
import org.maplibre.compose.expressions.dsl.const
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.layers.FillLayer
import org.maplibre.compose.layers.LineLayer
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.style.BaseStyle
import org.maplibre.compose.util.ClickResult
import org.maplibre.spatialk.geojson.Position

// Notification-zone subscriptions feature. Stage 1 covered kind='zone' management; Stage 2 adds
// the other two kinds (point+radius, custom polygon) plus the TYPE selector driving which
// picker shows. Still deferred: editing an existing subscription (only create/delete),
// is_active toggling, and actual push dispatch/matching -- this screen is purely management.
private enum class SubscriptionKindOption(val dbValue: String, val label: String) {
    ZONE("zone", "Zone"),
    POINT_RADIUS("point_radius", "Point + Radius"),
    CUSTOM_POLYGON("custom_polygon", "Custom Area")
}

private enum class SubscriptionDuration(val label: String, val durationMs: Long?) {
    PERMANENT("Permanent", null),
    TODAY("Today", 24L * 60 * 60 * 1000),
    THIS_WEEK("This Week", 7L * 24 * 60 * 60 * 1000)
}

private val RADIUS_OPTIONS_METERS = listOf(500.0, 1000.0, 2000.0, 5000.0)

@Composable
fun SubscriptionsScreen(onBack: () -> Unit, appPreferences: AppPreferences) {
    // Region picker deliberately omitted for this stage -- St. Lawrence has no seeded zone/point
    // data yet, so a picker would just show empty lists there. Hardcoded here rather than left
    // as a TODO so it's an explicit, visible decision.
    val region = Regions.COOK_INLET

    var subscriberId by remember { mutableStateOf<String?>(null) }
    var zones by remember { mutableStateOf<List<ZoneRecord>>(emptyList()) }
    var pointPresets by remember { mutableStateOf<List<PointPresetRecord>>(emptyList()) }
    var subscriptions by remember { mutableStateOf<List<SubscriptionRecord>>(emptyList()) }
    // Subscription ids where this subscriber's own 'verified_only' setting overlaps one of their
    // own 'all' subscriptions -- match_notification_recipients' most-restrictive-wins rule
    // (supabase/migrations/20260918000000_confidence_filter_most_restrictive_wins.sql) means the
    // stricter one silently applies to the overlap too. Drives the passive note on that row below;
    // refreshed alongside subscriptions itself since either side of an overlap can change it.
    var confidenceFilterOverlapIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isLoading by remember { mutableStateOf(true) }

    var selectedKind by remember { mutableStateOf(SubscriptionKindOption.ZONE) }
    var selectedConfidenceFilter by remember { mutableStateOf(SubscriptionConfidenceFilter.ALL) }
    var isSubscribing by remember { mutableStateOf(false) }
    var actionError by remember { mutableStateOf<String?>(null) }

    // Zone kind
    var selectedZoneSlug by remember { mutableStateOf<String?>(null) }

    // Point+radius kind -- a preset selection and a custom pin are mutually exclusive; picking
    // one clears the other.
    var selectedPresetSlug by remember { mutableStateOf<String?>(null) }
    var customPointLat by remember { mutableStateOf<Double?>(null) }
    var customPointLng by remember { mutableStateOf<Double?>(null) }
    var selectedRadiusMeters by remember { mutableStateOf(RADIUS_OPTIONS_METERS.first()) }
    var selectedDuration by remember { mutableStateOf(SubscriptionDuration.PERMANENT) }
    var showPointPicker by remember { mutableStateOf(false) }

    // Custom polygon kind
    var polygonVertices by remember { mutableStateOf<List<Pair<Double, Double>>>(emptyList()) }
    var showPolygonPicker by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()

    suspend fun refreshSubscriptions(id: String) {
        subscriptions = SupabaseApi.getSubscriptions(id)
        confidenceFilterOverlapIds = SupabaseApi.getConfidenceFilterOverlaps(id)
    }

    LaunchedEffect(Unit) {
        val id = appPreferences.getOrCreateSubscriberId()
        subscriberId = id
        zones = SupabaseApi.getZones(region.id)
        pointPresets = SupabaseApi.getPointPresets(region.id)
        refreshSubscriptions(id)
        isLoading = false
    }

    fun subscribe() {
        val id = subscriberId ?: return
        if (isSubscribing) return

        // Unified on SubscriptionCreateResult (not just Boolean) so the zone branch can report
        // ALREADY_EXISTS distinctly -- see that enum's own comment. Point/polygon have no such
        // outcome (no unique constraint backs them, see the migration adding the zone one for
        // why); their plain Boolean just maps straight onto SUCCESS/ERROR here.
        val performCreate = when (selectedKind) {
            SubscriptionKindOption.ZONE ->
                zones.find { it.slug == selectedZoneSlug }?.let { zone ->
                    suspend { SupabaseApi.createZoneSubscription(id, zone.id, selectedConfidenceFilter) }
                }

            SubscriptionKindOption.POINT_RADIUS -> {
                val preset = pointPresets.find { it.slug == selectedPresetSlug }
                val lat = preset?.lat ?: customPointLat
                val lng = preset?.lng ?: customPointLng
                if (lat != null && lng != null) {
                    suspend {
                        val ok = SupabaseApi.createPointSubscription(
                            subscriberId = id,
                            lat = lat,
                            lng = lng,
                            radiusMeters = selectedRadiusMeters,
                            confidenceFilter = selectedConfidenceFilter,
                            label = preset?.name ?: "Custom Point",
                            expiresAtEpochMs = selectedDuration.durationMs?.let { currentTimeMillis() + it }
                        )
                        if (ok) SubscriptionCreateResult.SUCCESS else SubscriptionCreateResult.ERROR
                    }
                } else null
            }

            SubscriptionKindOption.CUSTOM_POLYGON ->
                if (polygonVertices.size >= 3) {
                    suspend {
                        val ok = SupabaseApi.createPolygonSubscription(id, polygonVertices, selectedConfidenceFilter)
                        if (ok) SubscriptionCreateResult.SUCCESS else SubscriptionCreateResult.ERROR
                    }
                } else null
        } ?: return

        scope.launch {
            isSubscribing = true
            actionError = null
            when (performCreate()) {
                SubscriptionCreateResult.SUCCESS -> {
                    selectedZoneSlug = null
                    selectedPresetSlug = null
                    customPointLat = null
                    customPointLng = null
                    selectedRadiusMeters = RADIUS_OPTIONS_METERS.first()
                    selectedDuration = SubscriptionDuration.PERMANENT
                    polygonVertices = emptyList()
                    refreshSubscriptions(id)
                }
                SubscriptionCreateResult.ALREADY_EXISTS -> {
                    // Shouldn't normally be reachable -- the zone chips below already disable an
                    // already-watched zone -- but a second device subscribing to the same zone at
                    // the same moment can still race past that client-side check, and the
                    // partial unique index is what actually rejects it. Refresh so this device's
                    // own list picks up whatever the other one just created.
                    actionError = "Already watching that zone."
                    refreshSubscriptions(id)
                }
                SubscriptionCreateResult.ERROR -> {
                    actionError = "Couldn't create that subscription. Try again."
                }
            }
            isSubscribing = false
        }
    }

    fun unsubscribe(subscriptionId: String) {
        val id = subscriberId ?: return
        scope.launch {
            actionError = null
            val success = SupabaseApi.deleteSubscription(subscriptionId)
            if (success) {
                refreshSubscriptions(id)
            } else {
                actionError = "Couldn't remove that subscription. Try again."
            }
        }
    }

    // Zones this subscriber already actively watches -- drives both the zone chips (below,
    // rendered disabled/checked instead of selectable) and isSubscribeEnabled's own belt-and-
    // suspenders check, so a stale selectedZoneSlug from just before a refresh can't slip an
    // insert through the UI that the partial unique index would only reject after a round trip.
    val subscribedZoneIds = subscriptions
        .filter { it.isActive && it.kind == "zone" }
        .mapNotNull { it.zoneId }
        .toSet()

    val isSubscribeEnabled = !isSubscribing && when (selectedKind) {
        SubscriptionKindOption.ZONE ->
            selectedZoneSlug != null &&
                zones.find { it.slug == selectedZoneSlug }?.id !in subscribedZoneIds
        SubscriptionKindOption.POINT_RADIUS ->
            selectedPresetSlug != null || (customPointLat != null && customPointLng != null)
        SubscriptionKindOption.CUSTOM_POLYGON -> polygonVertices.size >= 3
    }

    AppBackground {
    Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("ALERTS", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
            TextButton(onClick = onBack) {
                Text("← BACK", color = Color.Yellow, fontWeight = FontWeight.Bold)
            }
        }

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Color.Yellow)
            }
        } else {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                SubscriptionSection(title = "WATCHING") {
                    if (subscriptions.isEmpty()) {
                        Text(
                            "Not watching anything yet — subscribe below to get notified about sightings.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    } else {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            subscriptions.forEach { subscription ->
                                val confidenceLabel = SubscriptionConfidenceFilter.entries
                                    .find { it.dbValue == subscription.confidenceFilter }?.label
                                    ?: subscription.confidenceFilter
                                val (title, subtitle) = when (subscription.kind) {
                                    "zone" ->
                                        (zones.find { it.id == subscription.zoneId }?.name ?: "Unknown zone") to confidenceLabel
                                    "point_radius" -> {
                                        val radiusText = subscription.radiusMeters?.let { formatRadiusMeters(it) }
                                        val expiryText = subscription.expiresAt?.let { "until ${it.substringBefore('T')}" }
                                        val detail = listOfNotNull(radiusText, confidenceLabel, expiryText)
                                            .joinToString(" · ")
                                        (subscription.label ?: "Custom Point") to detail
                                    }
                                    "custom_polygon" -> "Custom area" to confidenceLabel
                                    else -> (subscription.label ?: subscription.kind) to confidenceLabel
                                }
                                SubscriptionListItem(
                                    title = title,
                                    subtitle = subtitle,
                                    note = if (subscription.id in confidenceFilterOverlapIds) {
                                        "Your $title (verified only) setting applies to overlapping zones."
                                    } else null,
                                    onRemove = { unsubscribe(subscription.id) }
                                )
                            }
                        }
                    }
                }

                SubscriptionSection(title = "TYPE") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SubscriptionKindOption.entries.forEach { option ->
                            FilterChip(
                                selected = selectedKind == option,
                                onClick = { selectedKind = option },
                                label = { Text(option.label, fontSize = 12.sp) },
                                colors = subscriptionChipColors()
                            )
                        }
                    }
                }

                when (selectedKind) {
                    SubscriptionKindOption.ZONE -> SubscriptionSection(title = "ZONE") {
                        if (zones.isEmpty()) {
                            Text(
                                "No named zones for ${region.name} yet.",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                zones.forEach { zone ->
                                    // Already-watching zones render permanently selected (yellow,
                                    // checkmarked) and disabled -- this is the actual fix for the
                                    // duplicate-subscription bug: nothing to tap means nothing to
                                    // insert twice, rather than letting the tap through and
                                    // bouncing off the partial unique index afterward.
                                    val isAlreadyWatching = zone.id in subscribedZoneIds
                                    FilterChip(
                                        selected = isAlreadyWatching || selectedZoneSlug == zone.slug,
                                        enabled = !isAlreadyWatching,
                                        onClick = { selectedZoneSlug = zone.slug },
                                        label = {
                                            Text(
                                                if (isAlreadyWatching) "✓ ${zone.name}" else zone.name,
                                                fontSize = 12.sp
                                            )
                                        },
                                        colors = subscriptionChipColors()
                                    )
                                }
                            }
                        }
                    }

                    SubscriptionKindOption.POINT_RADIUS -> SubscriptionSection(title = "POINT") {
                        if (pointPresets.isEmpty()) {
                            Text(
                                "No named points for ${region.name} yet — drop a custom pin below.",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 12.sp
                            )
                        } else {
                            Row(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                modifier = Modifier.horizontalScroll(rememberScrollState())
                            ) {
                                pointPresets.forEach { preset ->
                                    FilterChip(
                                        selected = selectedPresetSlug == preset.slug,
                                        onClick = {
                                            selectedPresetSlug = preset.slug
                                            selectedRadiusMeters = preset.defaultRadiusMeters
                                            customPointLat = null
                                            customPointLng = null
                                        },
                                        label = { Text(preset.name, fontSize = 12.sp) },
                                        colors = subscriptionChipColors()
                                    )
                                }
                            }
                            Spacer(Modifier.height(8.dp))
                        }

                        val hasCustomPin = customPointLat != null && customPointLng != null
                        Button(
                            onClick = { showPointPicker = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (hasCustomPin) Color(0xFFFF9800) else Color.Black.copy(alpha = 0.75f)
                            ),
                            border = if (hasCustomPin) null else
                                androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                        ) {
                            Text(
                                if (hasCustomPin) "📍 CUSTOM PIN SET — TAP TO CHANGE" else "📍 DROP A CUSTOM PIN",
                                color = if (hasCustomPin) Color.Black else Color.Yellow,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(Modifier.height(12.dp))
                        Text(
                            "RADIUS",
                            color = Color.White.copy(alpha = 0.6f),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            letterSpacing = 1.sp
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            RADIUS_OPTIONS_METERS.forEach { option ->
                                FilterChip(
                                    selected = selectedRadiusMeters == option,
                                    onClick = { selectedRadiusMeters = option },
                                    label = { Text(formatRadiusMeters(option), fontSize = 12.sp) },
                                    colors = subscriptionChipColors()
                                )
                            }
                        }
                    }

                    SubscriptionKindOption.CUSTOM_POLYGON -> SubscriptionSection(title = "AREA") {
                        if (polygonVertices.size >= 3) {
                            Text(
                                "${polygonVertices.size}-point custom area selected.",
                                color = Color.White,
                                fontSize = 13.sp
                            )
                            Spacer(Modifier.height(8.dp))
                        }
                        Button(
                            onClick = { showPolygonPicker = true },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (polygonVertices.size >= 3) Color(0xFFFF9800) else Color.Black.copy(alpha = 0.75f)
                            ),
                            border = if (polygonVertices.size >= 3) null else
                                androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                        ) {
                            Text(
                                if (polygonVertices.size >= 3) "✏ REDRAW AREA" else "✏ DRAW A CUSTOM AREA",
                                color = if (polygonVertices.size >= 3) Color.Black else Color.Yellow,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }

                SubscriptionSection(title = "NOTIFY ME FOR") {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SubscriptionConfidenceFilter.entries.forEach { option ->
                            FilterChip(
                                selected = selectedConfidenceFilter == option,
                                onClick = { selectedConfidenceFilter = option },
                                label = { Text(option.label, fontSize = 12.sp) },
                                colors = subscriptionChipColors()
                            )
                        }
                    }
                }

                if (selectedKind == SubscriptionKindOption.POINT_RADIUS) {
                    SubscriptionSection(title = "DURATION") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            SubscriptionDuration.entries.forEach { option ->
                                FilterChip(
                                    selected = selectedDuration == option,
                                    onClick = { selectedDuration = option },
                                    label = { Text(option.label, fontSize = 12.sp) },
                                    colors = subscriptionChipColors()
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                Button(
                    onClick = { subscribe() },
                    enabled = isSubscribeEnabled,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSubscribing) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("SUBSCRIBE", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                }

                actionError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF5252), fontSize = 12.sp)
                }

                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showPointPicker) {
        PointPickerDialog(
            region = region,
            initialLat = customPointLat,
            initialLng = customPointLng,
            initialRadiusMeters = selectedRadiusMeters,
            onDismiss = { showPointPicker = false },
            onConfirm = { lat, lng, radius ->
                customPointLat = lat
                customPointLng = lng
                selectedRadiusMeters = radius
                selectedPresetSlug = null
                showPointPicker = false
            }
        )
    }

    if (showPolygonPicker) {
        PolygonPickerDialog(
            region = region,
            initialVertices = polygonVertices,
            onDismiss = { showPolygonPicker = false },
            onConfirm = { vertices ->
                polygonVertices = vertices
                showPolygonPicker = false
            }
        )
    }
    }
}

// Full-screen picker for a point_radius subscription: tap the map to drop/move a pin, pick a
// radius, see a live meter-accurate preview circle (built via the same bearing/distance math
// the zone-seeding data and sighting sectors already use).
@Composable
private fun PointPickerDialog(
    region: RegionConfig,
    initialLat: Double?,
    initialLng: Double?,
    initialRadiusMeters: Double,
    onDismiss: () -> Unit,
    onConfirm: (lat: Double, lng: Double, radiusMeters: Double) -> Unit
) {
    var pickedLat by remember { mutableStateOf(initialLat ?: region.defaultCenterLat) }
    var pickedLng by remember { mutableStateOf(initialLng ?: region.defaultCenterLng) }
    var radiusMeters by remember { mutableStateOf(initialRadiusMeters) }

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(longitude = pickedLng, latitude = pickedLat),
            zoom = region.defaultZoom
        )
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF102A2E)) {
            Box(modifier = Modifier.fillMaxSize()) {
                MaplibreMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraState = cameraState,
                    baseStyle = BaseStyle.Uri("https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"),
                    options = getMapOptions(),
                    onMapClick = { pos, _ ->
                        pickedLat = pos.latitude
                        pickedLng = pos.longitude
                        ClickResult.Consume
                    }
                ) {
                    val circleSource = rememberGeoJsonSource(
                        data = GeoJsonData.JsonString(buildRadiusCircleGeoJson(pickedLat, pickedLng, radiusMeters))
                    )
                    FillLayer(id = "picker-radius-fill", source = circleSource, color = const(Color(0xFF00E5FF)), opacity = const(0.25f))
                    LineLayer(id = "picker-radius-outline", source = circleSource, color = const(Color(0xFF00E5FF)), width = const(1.5.dp))

                    val pinSource = rememberGeoJsonSource(
                        data = GeoJsonData.JsonString(buildPointMarkerGeoJson(pickedLat, pickedLng))
                    )
                    CircleLayer(
                        id = "picker-pin",
                        source = pinSource,
                        color = const(Color.Yellow),
                        radius = const(8.dp),
                        strokeColor = const(Color.Black),
                        strokeWidth = const(2.dp)
                    )
                }

                Text(
                    "Tap the map to drop a pin",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(16.dp)
                ) {
                    Text("RADIUS", color = Color.Yellow, fontSize = 12.sp, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        RADIUS_OPTIONS_METERS.forEach { option ->
                            FilterChip(
                                selected = radiusMeters == option,
                                onClick = { radiusMeters = option },
                                label = { Text(formatRadiusMeters(option), fontSize = 12.sp) },
                                colors = subscriptionChipColors()
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                        TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                            Text("CANCEL", color = Color.White)
                        }
                        Button(
                            onClick = { onConfirm(pickedLat, pickedLng, radiusMeters) },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("USE THIS LOCATION", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

// Full-screen picker for a custom_polygon subscription: each tap appends a vertex, drawing a
// live outline/fill preview; Undo drops the last vertex, Finish requires at least 3.
@Composable
private fun PolygonPickerDialog(
    region: RegionConfig,
    initialVertices: List<Pair<Double, Double>>,
    onDismiss: () -> Unit,
    onConfirm: (vertices: List<Pair<Double, Double>>) -> Unit
) {
    var vertices by remember { mutableStateOf(initialVertices) }

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(longitude = region.defaultCenterLng, latitude = region.defaultCenterLat),
            zoom = region.defaultZoom
        )
    )

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(modifier = Modifier.fillMaxSize(), color = Color(0xFF102A2E)) {
            Box(modifier = Modifier.fillMaxSize()) {
                MaplibreMap(
                    modifier = Modifier.fillMaxSize(),
                    cameraState = cameraState,
                    baseStyle = BaseStyle.Uri("https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"),
                    options = getMapOptions(),
                    onMapClick = { pos, _ ->
                        vertices = vertices + (pos.latitude to pos.longitude)
                        ClickResult.Consume
                    }
                ) {
                    if (vertices.size >= 2) {
                        val lineSource = rememberGeoJsonSource(data = GeoJsonData.JsonString(buildVertexLineGeoJson(vertices)))
                        LineLayer(id = "picker-polygon-outline", source = lineSource, color = const(Color(0xFF00E5FF)), width = const(2.dp))
                    }
                    if (vertices.size >= 3) {
                        val fillSource = rememberGeoJsonSource(data = GeoJsonData.JsonString(buildClosedPolygonGeoJson(vertices)))
                        FillLayer(id = "picker-polygon-fill", source = fillSource, color = const(Color(0xFF00E5FF)), opacity = const(0.25f))
                    }
                    if (vertices.isNotEmpty()) {
                        val vertexSource = rememberGeoJsonSource(data = GeoJsonData.JsonString(buildVertexPointsGeoJson(vertices)))
                        CircleLayer(
                            id = "picker-polygon-vertices",
                            source = vertexSource,
                            color = const(Color.Yellow),
                            radius = const(6.dp),
                            strokeColor = const(Color.Black),
                            strokeWidth = const(2.dp)
                        )
                    }
                }

                Text(
                    if (vertices.size < 3) "Tap the map to add points (need at least 3)" else "${vertices.size} points — tap Finish when done",
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .statusBarsPadding()
                        .padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(8.dp))
                        .padding(horizontal = 12.dp, vertical = 8.dp)
                )

                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(Color.Black.copy(alpha = 0.85f))
                        .padding(16.dp)
                ) {
                    TextButton(onClick = onDismiss, modifier = Modifier.weight(1f)) {
                        Text("CANCEL", color = Color.White)
                    }
                    TextButton(
                        onClick = { if (vertices.isNotEmpty()) vertices = vertices.dropLast(1) },
                        enabled = vertices.isNotEmpty(),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("UNDO", color = Color(0xFFFF9800))
                    }
                    Button(
                        onClick = { onConfirm(vertices) },
                        enabled = vertices.size >= 3,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("FINISH", color = Color.Black, fontWeight = FontWeight.Black, fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SubscriptionListItem(title: String, subtitle: String, note: String? = null, onRemove: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.08f), shape = RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column {
            Text(title, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
            // Passive, not a warning -- most-restrictive-wins is a quiet behavior change, not
            // something wrong with this subscription. See this note's own callers.
            if (note != null) {
                Text(note, color = Color.White.copy(alpha = 0.45f), fontSize = 10.sp)
            }
        }
        TextButton(onClick = onRemove) {
            Text("REMOVE", color = Color(0xFFFF5252), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun subscriptionChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Color.Yellow,
    selectedLabelColor = Color.Black,
    containerColor = Color.White.copy(alpha = 0.1f),
    labelColor = Color.White
)

@Composable
private fun SubscriptionSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        Text(
            text = title,
            color = Color.Yellow,
            fontSize = 13.sp,
            fontWeight = FontWeight.Black,
            letterSpacing = 1.sp
        )
        Spacer(Modifier.height(8.dp))
        content()
    }
    HorizontalDivider(color = Color.White.copy(alpha = 0.15f), thickness = 1.dp)
}

private fun formatRadiusMeters(meters: Double): String {
    if (meters < 1000.0) return "${meters.toLong()} m"
    val km = meters / 1000.0
    val rounded = kotlin.math.round(km * 10) / 10.0
    val kmText = if (rounded == kotlin.math.floor(rounded)) rounded.toLong().toString() else rounded.toString()
    return "$kmText km"
}

// A radius-meters circle, approximated as a many-sided polygon via the same bearing/distance
// projection already used for sighting sectors and the zone-seeding data (see
// HeadingDistance.kt's destinationPoint()) -- MapLibre's CircleLayer radius is in screen dp,
// not ground meters, so it can't draw this directly.
private fun buildRadiusCircleGeoJson(lat: Double, lng: Double, radiusMeters: Double, segments: Int = 48): String {
    val coordinates = (0..segments).joinToString(", ") { i ->
        val bearing = 360.0 * i / segments
        val (pointLat, pointLng) = destinationPoint(lat, lng, bearing, radiusMeters)
        "[$pointLng, $pointLat]"
    }
    return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$coordinates]]},"properties":{}}]}"""
}

private fun buildPointMarkerGeoJson(lat: Double, lng: Double): String =
    """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Point","coordinates":[$lng,$lat]},"properties":{}}]}"""

private fun buildVertexLineGeoJson(vertices: List<Pair<Double, Double>>): String {
    val coords = vertices.joinToString(",") { (lat, lng) -> "[$lng,$lat]" }
    return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"LineString","coordinates":[$coords]},"properties":{}}]}"""
}

private fun buildClosedPolygonGeoJson(vertices: List<Pair<Double, Double>>): String {
    val ring = vertices + vertices.first()
    val coords = ring.joinToString(",") { (lat, lng) -> "[$lng,$lat]" }
    return """{"type":"FeatureCollection","features":[{"type":"Feature","geometry":{"type":"Polygon","coordinates":[[$coords]]},"properties":{}}]}"""
}

private fun buildVertexPointsGeoJson(vertices: List<Pair<Double, Double>>): String {
    val features = vertices.joinToString(",") { (lat, lng) ->
        """{"type":"Feature","geometry":{"type":"Point","coordinates":[$lng,$lat]},"properties":{}}"""
    }
    return """{"type":"FeatureCollection","features":[$features]}"""
}
