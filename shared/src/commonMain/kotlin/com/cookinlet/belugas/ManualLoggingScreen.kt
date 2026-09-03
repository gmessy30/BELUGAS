package com.cookinlet.belugas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.Path
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.camera.*
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.spatialk.geojson.Feature
import org.maplibre.spatialk.geojson.FeatureCollection
import org.maplibre.spatialk.geojson.Point
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualLoggingScreen(
    storage: LocalFileStorage,
    locationService: LocationService,
    appPreferences: AppPreferences,
    region: RegionConfig = Regions.COOK_INLET,
    onDoneClick: () -> Unit,
    onOpenMenuClick: () -> Unit
) {
    // Sighting Position State (Defaults safely to region center)
    var sightingLat by remember { mutableStateOf(region.defaultCenterLat) }
    var sightingLng by remember { mutableStateOf(region.defaultCenterLng) }
    var sightingAlt by remember { mutableStateOf(0.0) }

    // Pod Metadata State
    // Absolute compass direction the animal was heading -- not relative to the observer (there
    // is no reliable observer vantage point in this flow to be relative to; see
    // CompassBearingPicker's doc comment). Optional, null = not recorded, matching
    // travelBearingDegrees being optional on SightingRecord.
    var travelBearingDegrees by remember { mutableStateOf<Double?>(null) }
    var whiteCount by remember { mutableStateOf(0) }
    var greyCount by remember { mutableStateOf(0) }
    var calfCount by remember { mutableStateOf(0) }
    var unknownCount by remember { mutableStateOf(0) }

    // Attribution & Timestamp State
    var observerType by remember { mutableStateOf(ObserverType.SELF) }
    var selectedTimestampMs by remember { mutableStateOf(currentTimeMillis()) }
    var showDatePicker by remember { mutableStateOf(false) }

    // Geofence & Save Controls
    var showGeofenceWarning by remember { mutableStateOf(false) }
    var showZeroCountWarning by remember { mutableStateOf(false) }
    // Coarse outer-geofence hard reject (GeofenceUtils.isWithinOuterGeofence), checked before
    // isWhalePositionVerified -- see that dialog's own text for why there's no SAVE ANYWAY.
    var showOutsideOuterGeofenceDialog by remember { mutableStateOf(false) }
    var pendingRecord by remember { mutableStateOf<SightingRecord?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var isRecentering by remember { mutableStateOf(false) }
    // Only true while the online coastline-channel fallback (GeofenceUtils.
    // isWithinCoastlineChannelFallback) is running, i.e. only when the buffer-based whale-
    // position check (GeofenceUtils.isWhalePositionVerified) found no well-sourced data near
    // the point at all -- never shown on a normal (resolved) submit.
    var isCheckingCoastlineFallback by remember { mutableStateOf(false) }

    // Uncertainty radius for the dropped pin -- flow-agnostic (no isAerial distinction, unlike
    // LoggingScreen's projected-distance bucket): an aerial pin-dropper can simply choose a
    // larger radius themselves rather than the picker guessing from altitude. Always resolved
    // via DistanceBucket.radiusMeters(isAerial = false) -- see UncertaintyButton below.
    var uncertaintyBucket by remember { mutableStateOf<DistanceBucket?>(null) }

    val scope = rememberCoroutineScope()

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(longitude = sightingLng, latitude = sightingLat),
            zoom = 12.0
        )
    )

    suspend fun recenterOnGps(zoom: Double = 12.0) {
        try {
            val coords = locationService.getCurrentLocation()
            if (coords != null) {
                sightingLat = coords.latitude
                sightingLng = coords.longitude
                sightingAlt = coords.altitudeMeters
                cameraState.position = CameraPosition(
                    target = Position(longitude = sightingLng, latitude = sightingLat),
                    zoom = zoom
                )
            }
        } catch (e: Exception) {
            e.printStackTrace() // Fallback stays on default region coordinates
        }
    }

    // Safely update GPS position on boot
    LaunchedEffect(Unit) {
        recenterOnGps()
    }

    fun saveAndFinish(record: SightingRecord) {
        scope.launch {
            try {
                // Ensure we use the latest pin position for the record -- the pin IS the whale
                // position under the redesign (no projection), same "use the latest map center"
                // safety net the old lat/lng re-stamp here used to provide.
                val center = cameraState.position.target
                val updatedRecord = record.copy(
                    whaleLat = center.latitude,
                    whaleLng = center.longitude
                )
                OfflineSightingRepository.queueSighting(storage, updatedRecord)
                // Wait for the sync attempt to actually finish before handing control back --
                // otherwise the caller's post-save refresh races the sync and may run before
                // the new sighting has landed remotely.
                SyncEngine.processQueueInBackground(scope, storage).join()
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                onDoneClick()
            }
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // --- 1. FULL-SCREEN INTERACTIVE MAP BACKDROP ---
        // Forcing a surface re-bind ensures the GL context isn't lost on screen transitions
        key(Unit) {
            MaplibreMap(
                modifier = Modifier.fillMaxSize(),
                baseStyle = BaseStyle.Uri("https://basemaps.cartocdn.com/gl/positron-gl-style/style.json"),
                cameraState = cameraState,
                options = getMapOptions()
            )
        }

        // 2. FIXED CENTER TARGET MARKER (Canvas-drawn pin to avoid C++ engine collisions)
        Canvas(
            modifier = Modifier
                .size(48.dp)
                .align(Alignment.Center)
                .offset(y = (-24).dp)
        ) {
            val w = size.width
            val h = size.height
            val path = Path().apply {
                moveTo(w * 0.5f, h)
                cubicTo(w * 0.2f, h * 0.7f, 0f, h * 0.5f, 0f, h * 0.35f)
                arcTo(
                    rect = androidx.compose.ui.geometry.Rect(0f, 0f, w, h * 0.7f),
                    startAngleDegrees = 180f,
                    sweepAngleDegrees = 180f,
                    forceMoveTo = false
                )
                cubicTo(w, h * 0.5f, w * 0.8f, h * 0.7f, w * 0.5f, h)
                close()
            }
            drawPath(path, Color.Red, style = Fill)
            drawCircle(Color.White, radius = w * 0.15f, center = androidx.compose.ui.geometry.Offset(w * 0.5f, h * 0.35f))
        }

        // 2b. LIVE COORDINATE READOUT -- surfaces the exact target coordinates a submit would
        // use (cameraState.position.target, same source as the SUBMIT button below), so a
        // geofence-rejected location can be read directly off the screen and reported precisely
        // instead of from memory. Top-end (below MENU), not top-center -- center is the AWAY
        // direction arrow's own space, and this used to sit right in its notch.
        Text(
            text = "${formatCoord(cameraState.position.target.latitude)}, ${formatCoord(cameraState.position.target.longitude)}",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopEnd)
                .statusBarsPadding()
                .padding(top = 68.dp, end = 16.dp)
                .background(Color.Black.copy(alpha = 0.75f), shape = RoundedCornerShape(6.dp))
                .padding(horizontal = 10.dp, vertical = 4.dp)
        )

        // --- 2. TOP HEADER BAR (DONE + RETURN TO CAMERA) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Upper Left: <- DONE
            Button(
                onClick = {
                    if (isSaving) return@Button
                    if (whiteCount + greyCount + calfCount + unknownCount == 0) {
                        showZeroCountWarning = true
                        return@Button
                    }
                    isSaving = true

                    scope.launch {
                        // The pin IS the whale position -- no projection, no heading/distance
                        // reading needed for placement.
                        val targetCenter = cameraState.position.target
                        val uncertaintyRadius = (uncertaintyBucket ?: DistanceBucket.MEDIUM).radiusMeters(isAerial = false)

                        // This device's own persistent id, sent so the server can compute
                        // observerTier at insert -- never read back by the app (anon has no
                        // SELECT grant on sightings.subscriber_id at all, see
                        // 20260903010000_add_observer_tier_system.sql).
                        val subscriberId = appPreferences.getOrCreateSubscriberId()

                        val record = SightingRecord(
                            whaleLat = targetCenter.latitude,
                            whaleLng = targetCenter.longitude,
                            uncertaintyRadiusMeters = uncertaintyRadius,
                            uncertaintyBucket = (uncertaintyBucket ?: DistanceBucket.MEDIUM).name,
                            travelBearingDegrees = travelBearingDegrees,
                            travelBearingSource = if (travelBearingDegrees != null) TravelBearingSource.MANUAL.name else null,
                            positionSource = PositionSource.PIN.name,
                            countWhites = whiteCount,
                            countGreys = greyCount,
                            countCalves = calfCount,
                            countUnknown = unknownCount,
                            observedAtEpochMs = selectedTimestampMs,
                            observerType = observerType.name,
                            subscriberId = subscriberId
                        )

                        if (!GeofenceUtils.isWithinOuterGeofence(targetCenter.latitude, targetCenter.longitude)) {
                            // Coarse hard reject, ahead of the real geofence flow -- see
                            // GeofenceUtils.isWithinOuterGeofence's own comment. No SAVE
                            // ANYWAY, no pendingRecord, no soft warning: this location isn't
                            // remotely Cook Inlet.
                            showOutsideOuterGeofenceDialog = true
                            isSaving = false
                            return@launch
                        }

                        when (GeofenceUtils.isWhalePositionVerified(targetCenter.latitude, targetCenter.longitude, uncertaintyRadius)) {
                            true -> saveAndFinish(record.copy(isGeofenceVerified = true))
                            false -> {
                                pendingRecord = record
                                showGeofenceWarning = true
                                isSaving = false
                            }
                            null -> {
                                // Only reachable once the buffer check found no well-sourced
                                // data near this point at all -- the online fallback never
                                // runs, and this loading state never shows, on a normal
                                // (resolved) submit.
                                isCheckingCoastlineFallback = true
                                val validByChannel = GeofenceUtils.isWithinCoastlineChannelFallback(
                                    targetCenter.latitude, targetCenter.longitude
                                )
                                isCheckingCoastlineFallback = false
                                if (validByChannel) {
                                    saveAndFinish(record.copy(isGeofenceVerified = true))
                                } else {
                                    pendingRecord = record
                                    showGeofenceWarning = true
                                    isSaving = false
                                }
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.85f)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("← SUBMIT", color = Color.Yellow, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }

            // Upper Right: Return to Main Menu
            Button(
                onClick = onOpenMenuClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("☰ MENU", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Black)
            }
        }

        // --- 3b. RECENTER ON GPS BUTTON ---
        Button(
            onClick = {
                if (isRecentering) return@Button
                isRecentering = true
                scope.launch {
                    try {
                        recenterOnGps(zoom = cameraState.position.zoom)
                    } finally {
                        isRecentering = false
                    }
                }
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 16.dp, bottom = 220.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.85f)),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            if (isRecentering) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    color = Color.Yellow,
                    strokeWidth = 2.dp
                )
            } else {
                Text("📍 RECENTER", color = Color.Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
            }
        }

        // --- 4. FLOATING TRANSPARENT CONTROLS PANEL ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .padding(bottom = 8.dp)
        ) {
            // Row A: Attribution Toggle + Date Picker
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    modifier = Modifier
                        .background(Color.Black.copy(alpha = 0.75f), RoundedCornerShape(8.dp))
                        .border(1.dp, Color.White.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                        .padding(3.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .background(
                                if (observerType == ObserverType.SELF) Color(0xFFFF9800) else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { observerType = ObserverType.SELF }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "MY SIGHTING",
                            color = if (observerType == ObserverType.SELF) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(
                                if (observerType == ObserverType.OTHER) Color(0xFFFF9800) else Color.Transparent,
                                RoundedCornerShape(6.dp)
                            )
                            .clickable { observerType = ObserverType.OTHER }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            "REPORTED BY OTHER",
                            color = if (observerType == ObserverType.OTHER) Color.Black else Color.White,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                Button(
                    onClick = { showDatePicker = true },
                    colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.75f)),
                    shape = RoundedCornerShape(8.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                ) {
                    Text("📅 SET DATE / TIME", color = Color.Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                }
            }

            // Row A2: Uncertainty ("how far away were they / how sure are you") and travel
            // direction ("which way were they heading?"). The pin itself is the whale position
            // now, so uncertainty is just how wide a circle to draw around it, and direction is
            // a direct compass pick rather than anything relative to an observer -- this screen
            // has no reliable vantage point to be relative to.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                UncertaintyButton(
                    uncertainty = uncertaintyBucket,
                    onConfirm = { bucket -> uncertaintyBucket = bucket }
                )
                Spacer(modifier = Modifier.width(8.dp))
                CompassBearingButton(
                    bearingDegrees = travelBearingDegrees,
                    onConfirm = { degrees -> travelBearingDegrees = degrees }
                )
            }

            // Row B: Stacked Counters (Floating Pills)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
                WhaleCounterTile("UNKNOWN", unknownCount, { unknownCount++ }, { if (unknownCount > 0) unknownCount-- })
                WhaleCounterTile("GREYS", greyCount, { greyCount++ }, { if (greyCount > 0) greyCount-- })
                WhaleCounterTile("CALVES", calfCount, { calfCount++ }, { if (calfCount > 0) calfCount-- })
                WhaleCounterTile("WHITES", whiteCount, { whiteCount++ }, { if (whiteCount > 0) whiteCount-- })
            }
        }

        // --- 5. DATE PICKER DIALOG ---
        if (showDatePicker) {
            val datePickerState = rememberDatePickerState(
                initialSelectedDateMillis = selectedTimestampMs
            )
            DatePickerDialog(
                onDismissRequest = { showDatePicker = false },
                confirmButton = {
                    TextButton(
                        onClick = {
                            datePickerState.selectedDateMillis?.let {
                                selectedTimestampMs = it
                            }
                            showDatePicker = false
                        }
                    ) { Text("OK", color = Color.Yellow) }
                },
                dismissButton = {
                    TextButton(onClick = { showDatePicker = false }) { Text("CANCEL", color = Color.White) }
                }
            ) {
                DatePicker(state = datePickerState)
            }
        }

        // --- ONLINE COASTLINE-CHANNEL FALLBACK CHECKING STATE ---
        // Only ever visible for the few seconds (bounded by GeofenceUtils'
        // COASTLINE_CHANNEL_FALLBACK_TIMEOUT_MS) between a synchronous geofence rejection and
        // that fallback resolving -- never shown for an accepted point.
        if (isCheckingCoastlineFallback) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Row(
                    modifier = Modifier
                        .background(Color(0xFF1E293B), shape = RoundedCornerShape(10.dp))
                        .padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        color = Color.Yellow,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(12.dp))
                    Text("CHECKING WATER DATA…", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        // --- GEOFENCE WARNING DIALOG ---
        if (showGeofenceWarning && pendingRecord != null) {
            val record = pendingRecord!!
            AlertDialog(
                onDismissRequest = { showGeofenceWarning = false },
                title = {
                    Text(
                        text = "Outside ${region.name} Geofence",
                        fontWeight = FontWeight.Bold
                    )
                },
                text = {
                    Text(
                        text = "Your selected location (${record.whaleLat}, ${record.whaleLng}) falls outside the primary observation sightline for ${region.name}.\n\nDo you still want to log this sighting?"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showGeofenceWarning = false
                            saveAndFinish(record)
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("SAVE ANYWAY", color = Color.Black, fontWeight = FontWeight.Black)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showGeofenceWarning = false }) {
                        Text("CANCEL", color = Color.White)
                    }
                },
                containerColor = Color(0xFF1E293B),
                titleContentColor = Color.White,
                textContentColor = Color.LightGray
            )
        }

        // --- OUTSIDE COOK INLET (COARSE OUTER GEOFENCE) HARD REJECT DIALOG ---
        // Plain messaging, single OK button -- no SAVE ANYWAY, unlike the real geofence warning
        // above. See GeofenceUtils.isWithinOuterGeofence's comment for why this check exists
        // and runs first.
        if (showOutsideOuterGeofenceDialog) {
            AlertDialog(
                onDismissRequest = { showOutsideOuterGeofenceDialog = false },
                title = {
                    Text(text = "Not a Cook Inlet Location", fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(text = "This app is for Cook Inlet beluga sightings.")
                },
                confirmButton = {
                    Button(
                        onClick = { showOutsideOuterGeofenceDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("OK", color = Color.Black, fontWeight = FontWeight.Black)
                    }
                },
                containerColor = Color(0xFF1E293B),
                titleContentColor = Color.White,
                textContentColor = Color.LightGray
            )
        }

        // --- ZERO-COUNT WARNING DIALOG ---
        if (showZeroCountWarning) {
            AlertDialog(
                onDismissRequest = { showZeroCountWarning = false },
                title = {
                    Text(text = "Invalid Report", fontWeight = FontWeight.Bold)
                },
                text = {
                    Text(text = "0 whales is not a valid report.")
                },
                confirmButton = {
                    Button(
                        onClick = { showZeroCountWarning = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("OK", color = Color.Black, fontWeight = FontWeight.Black)
                    }
                },
                containerColor = Color(0xFF1E293B),
                titleContentColor = Color.White,
                textContentColor = Color.LightGray
            )
        }
    }
}

/**
 * Confidence-phrased label for the uncertainty picker, e.g. "Confident (<150m)". DistanceBucket's
 * own shortLabel ("<150m", "~500m", ">1.2km") was written for the old "how far away" question and
 * reads as meaningless under "How sure are you?" with no framing, so this pairs a plain confidence
 * word with that same distance text. Only the displayed text differs -- underlying bucket/radius
 * unchanged.
 */
private fun DistanceBucket.confidenceLabel(): String {
    val word = when (this) {
        DistanceBucket.CLOSE -> "Confident"
        DistanceBucket.MEDIUM -> "Rough estimate"
        DistanceBucket.FAR -> "Not sure"
    }
    return "$word (${shortLabel(isAerial = false)})"
}

/**
 * Flow-agnostic uncertainty-radius picker for ManualLoggingScreen's dropped pin -- "how far
 * away were they / how sure are you", not a heading+distance projection like LoggingScreen's
 * HeadingDistanceButton (the pin already IS the position; this only sizes the circle drawn
 * around it). Reuses DistanceBucket's radii at the flow-agnostic isAerial=false scale -- an
 * aerial pin-dropper just picks a larger bucket rather than the picker guessing from altitude.
 * Labels are this screen's own confidence phrasing (see confidenceLabel above), not
 * DistanceBucket.shortLabel, which was written for a different question.
 */
@Composable
private fun UncertaintyButton(
    uncertainty: DistanceBucket?,
    modifier: Modifier = Modifier,
    onConfirm: (DistanceBucket) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    val summary = if (uncertainty != null) {
        "🎯 ±${uncertainty.shortLabel(isAerial = false)}"
    } else {
        "🎯 SET CONFIDENCE"
    }

    Button(
        onClick = { showDialog = true },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.75f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(summary, color = Color.Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }

    if (showDialog) {
        var selected by remember { mutableStateOf(uncertainty ?: DistanceBucket.MEDIUM) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("How sure are you?", fontWeight = FontWeight.Bold) },
            text = {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    DistanceBucket.entries.forEach { bucket ->
                        FilterChip(
                            selected = selected == bucket,
                            onClick = { selected = bucket },
                            label = { Text(bucket.confidenceLabel(), fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00E5FF),
                                selectedLabelColor = Color.Black
                            ),
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(selected); showDialog = false }) {
                    Text("OK", color = Color.Yellow)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) { Text("CANCEL", color = Color.White) }
            },
            containerColor = Color(0xFF1E293B),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
}

/**
 * Direct 8-point compass picker for the animal's absolute travel direction -- "which way were
 * they heading?", not relative AWAY/LEFT/RIGHT arrows like LoggingScreen's. Deliberately not a
 * reuse of that relative model: this screen has no reliable observer vantage point to be
 * relative to (a dropped pin can be placed from memory, panned to a spot watched from
 * elsewhere, or filed well after the fact), so a relative direction here would rest on an
 * unverifiable assumption about where the user is standing and which way they're facing.
 * Stores straight into SightingRecord.travelBearingDegrees at one of the 8 cardinal/intercardinal
 * values, travelBearingSource = MANUAL -- exactly what the map's own display snapping
 * (snapToNearestCompass8Degrees) reduces every bearing to anyway, so what's picked here is
 * exactly what gets drawn, with no lost precision either way.
 */
@Composable
private fun CompassBearingButton(
    bearingDegrees: Double?,
    modifier: Modifier = Modifier,
    onConfirm: (Double?) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    val summary = COMPASS_POINTS.firstOrNull { it.second == bearingDegrees }?.first
        ?.let { "🧭 $it" }
        ?: "🧭 SET DIRECTION"

    Button(
        onClick = { showDialog = true },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.75f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(summary, color = Color.Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }

    if (showDialog) {
        var selected by remember { mutableStateOf(bearingDegrees) }
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = { Text("Which way were they heading?", fontWeight = FontWeight.Bold) },
            text = {
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    COMPASS_POINTS.forEach { (label, deg) ->
                        FilterChip(
                            selected = selected == deg,
                            onClick = { selected = deg },
                            label = { Text(label, fontSize = 11.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color.Yellow,
                                selectedLabelColor = Color.Black
                            )
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { onConfirm(selected); showDialog = false }) {
                    Text("OK", color = Color.Yellow)
                }
            },
            dismissButton = {
                // Clears the selection (not just closes the dialog) -- travel direction is
                // optional, and this is the only way to explicitly unset a previously-picked one.
                TextButton(onClick = { onConfirm(null); showDialog = false }) {
                    Text("NOT SURE", color = Color.White)
                }
            },
            containerColor = Color(0xFF1E293B),
            titleContentColor = Color.White,
            textContentColor = Color.LightGray
        )
    }
}
