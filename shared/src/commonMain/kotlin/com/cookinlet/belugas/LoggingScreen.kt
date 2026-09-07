package com.cookinlet.belugas

import androidx.compose.foundation.Canvas
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
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

enum class PodDirection { NONE, AWAY, LEFT, RIGHT }

// Resolves a relative PodDirection selection into the absolute travel bearing actually stored
// (SightingRecord.travelBearingDegrees) -- the relative choice itself is never persisted.
// [baseBearingDegrees] is the observer's own compass reference: in LoggingScreen that's
// headingEstimate.degrees (the same reading used to project the whale position); in
// ManualLoggingScreen, which has no heading reading at all under the new design, it's the
// bearing from the observer's live GPS fix to the dropped pin (see that screen's submit
// handler). NONE returns null -- travel direction is optional and renders as a plain dot.
fun PodDirection.toAbsoluteTravelBearingDegrees(baseBearingDegrees: Double): Double? = when (this) {
    PodDirection.NONE -> null
    PodDirection.AWAY -> baseBearingDegrees
    PodDirection.LEFT -> ((baseBearingDegrees - 90.0) + 360.0) % 360.0
    PodDirection.RIGHT -> (baseBearingDegrees + 90.0) % 360.0
}

@Composable
fun LoggingScreen(
    capturedPhotoPath: String?,
    storage: LocalFileStorage,
    locationService: LocationService,
    appPreferences: AppPreferences,
    region: RegionConfig = Regions.COOK_INLET,
    onDoneClick: () -> Unit,
    onRetakeClick: () -> Unit,
    onNavigateToManualLogging: () -> Unit
) {
    var selectedDirection by remember { mutableStateOf(PodDirection.NONE) }
    var whiteCount by remember { mutableStateOf(0) }
    var greyCount by remember { mutableStateOf(0) }
    var calfCount by remember { mutableStateOf(0) }
    var unknownCount by remember { mutableStateOf(0) }

    var showGeofenceWarning by remember { mutableStateOf(false) }
    var showZeroCountWarning by remember { mutableStateOf(false) }
    // No heading was given, and CoastlineGeometry.projectOffshoreFallback couldn't place a
    // credible whale position either (no real coastline data near the observer, or no water
    // confirmed within its search cap). There is deliberately no SAVE ANYWAY for this case --
    // the only coordinates available are the observer's own raw GPS fix, and writing those into
    // whale_lat/whale_lng under a FALLBACK label would store exactly the observer position this
    // redesign exists to stop storing, mislabelled as the whale's. Losing the report is the
    // correct outcome here; ManualLoggingScreen's dropped pin is the way to place it by hand.
    var showCannotPlaceDialog by remember { mutableStateOf(false) }
    // Coarse outer-geofence hard reject (GeofenceUtils.isWithinOuterGeofence), checked before
    // isWhalePositionVerified -- see that dialog's own text for why there's no SAVE ANYWAY.
    var showOutsideOuterGeofenceDialog by remember { mutableStateOf(false) }
    var pendingRecord by remember { mutableStateOf<SightingRecord?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    // Only true while the online coastline-channel fallback (GeofenceUtils.
    // isWithinCoastlineChannelFallback) is running, i.e. only when the buffer-based whale-
    // position check (GeofenceUtils.isWhalePositionVerified) found no well-sourced data near
    // the point at all -- never shown on a normal (resolved) submit.
    var isCheckingCoastlineFallback by remember { mutableStateOf(false) }

    var headingEstimate by remember { mutableStateOf<HeadingEstimate?>(null) }
    var distanceBucket by remember { mutableStateOf<DistanceBucket?>(null) }

    // Approximate observer position/altitude, fetched once for the heading/distance picker's
    // aerial-vs-shore bucket sizing and live geofence hint. The submitted record still fetches a
    // fresh GPS reading at DONE time below, independent of this.
    var observerLat by remember { mutableStateOf(region.defaultCenterLat) }
    var observerLng by remember { mutableStateOf(region.defaultCenterLng) }
    var observerAltitude by remember { mutableStateOf(0.0) }
    LaunchedEffect(Unit) {
        try {
            val coords = locationService.getCurrentLocation()
            if (coords != null) {
                observerLat = coords.latitude
                observerLng = coords.longitude
                observerAltitude = coords.altitudeMeters
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    val isAerial = observerAltitude > 100.0

    val scope = rememberCoroutineScope()

    fun saveAndFinish(record: SightingRecord) {
        scope.launch {
            OfflineSightingRepository.queueSighting(storage, record, localPhotoPath = capturedPhotoPath)
            // Wait for the sync attempt to actually finish before handing control back --
            // otherwise the caller's post-save refresh races the sync and may run before the
            // new sighting has landed remotely.
            SyncEngine.processQueueInBackground(scope, storage).join()
            onDoneClick()
        }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        // --- 1. REAL CAPTURED PHOTO BACKGROUND ---
        if (!capturedPhotoPath.isNullOrEmpty()) {
            LocalPhotoPreview(
                filePath = capturedPhotoPath,
                modifier = Modifier.fillMaxSize()
            )
        } else {
            // Dark background if no photo file path is available
            Box(modifier = Modifier.fillMaxSize().background(Color.Black))
        }

        // Vignette Overlay
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.2f))
        )

        // --- 2. TOP HEADER (<- DONE & RETAKE ↻) ---
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .statusBarsPadding()
                .padding(16.dp)
                .align(Alignment.TopCenter),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = {
                    if (isSaving) return@Button
                    if (whiteCount + greyCount + calfCount + unknownCount == 0) {
                        showZeroCountWarning = true
                        return@Button
                    }
                    isSaving = true
                    scope.launch {
                        val coords = locationService.getCurrentLocation()
                        val currentLat = coords?.latitude ?: region.defaultCenterLat
                        val currentLng = coords?.longitude ?: region.defaultCenterLng
                        val currentAlt = coords?.altitudeMeters ?: 0.0

                        val heading = headingEstimate
                        val bucket = distanceBucket

                        // Travel direction needs a real base bearing -- only computed when a
                        // heading reading was actually confirmed, even if a direction arrow was
                        // tapped. Deriving it from computeDefaultOffshoreHeadingDegrees's
                        // algorithmic guess (as this used to) would manufacture "which way it
                        // was heading" from a bearing nobody measured, and labeling that MANUAL
                        // would conflate it with a real compass pick (e.g. ManualLoggingScreen's
                        // CompassBearingButton) under the same source tag -- exactly the kind of
                        // fabricated fact this redesign exists to avoid.
                        val travelBearing = heading?.let { h -> selectedDirection.toAbsoluteTravelBearingDegrees(h.degrees) }
                        val travelBearingSource = if (travelBearing != null) heading.source.name else null

                        // Real heading+distance -> project the whale position (PROJECTED).
                        // No heading given -> CoastlineGeometry's offshore-perpendicular guess
                        // (FALLBACK), or null if there's no real coastline data near the
                        // observer to guess from at all.
                        val position: WhalePositionEstimate? = if (heading != null && bucket != null) {
                            val radius = bucket.radiusMeters(currentAlt > 100.0)
                            val (projLat, projLng) = destinationPoint(currentLat, currentLng, heading.degrees, radius)
                            WhalePositionEstimate(projLat, projLng, radius, bucket.name, PositionSource.PROJECTED)
                        } else {
                            projectOffshoreFallback(currentLat, currentLng)?.let { (fbLat, fbLng) ->
                                WhalePositionEstimate(
                                    fbLat, fbLng, FALLBACK_UNCERTAINTY_RADIUS_METERS, null, PositionSource.FALLBACK
                                )
                            }
                        }

                        // This device's own persistent id, sent so the server can compute
                        // observerTier at insert -- never read back by the app (anon has no
                        // SELECT grant on sightings.subscriber_id at all, see
                        // 20260903010000_add_observer_tier_system.sql).
                        val subscriberId = appPreferences.getOrCreateSubscriberId()

                        fun buildRecord(pos: WhalePositionEstimate, verified: Boolean) = SightingRecord(
                            whaleLat = pos.lat,
                            whaleLng = pos.lng,
                            uncertaintyRadiusMeters = pos.uncertaintyRadiusMeters,
                            uncertaintyBucket = pos.uncertaintyBucket,
                            travelBearingDegrees = travelBearing,
                            travelBearingSource = travelBearingSource,
                            positionSource = pos.positionSource.name,
                            countWhites = whiteCount,
                            countGreys = greyCount,
                            countCalves = calfCount,
                            countUnknown = unknownCount,
                            observedAtEpochMs = currentTimeMillis(),
                            observerType = ObserverType.SELF.name,
                            isGeofenceVerified = verified,
                            subscriberId = subscriberId
                        )

                        suspend fun finishWith(pos: WhalePositionEstimate, passed: Boolean) {
                            // A FALLBACK position is in water almost by construction (the walk
                            // stops the moment it would leave water) -- treating that as real
                            // evidence would make "verified" trivially true for exactly the
                            // submissions with the least actual evidence behind them, so it's
                            // forced false here regardless of whether the check passed.
                            val verified = passed && pos.positionSource != PositionSource.FALLBACK
                            if (passed) {
                                saveAndFinish(buildRecord(pos, verified = verified))
                            } else {
                                pendingRecord = buildRecord(pos, verified = false)
                                showGeofenceWarning = true
                                isSaving = false
                            }
                        }

                        if (position == null) {
                            // No heading given, and no real coastline geometry/water found to
                            // guess a position from either -- there is no credible whale
                            // position to save, not even a rough one, and no SAVE ANYWAY here:
                            // the only coordinates on hand are the observer's own raw GPS fix,
                            // and saving those under a whale-position label is exactly what this
                            // redesign exists to prevent. Direct the user to the manual flow
                            // instead of silently mislabelling a position.
                            showCannotPlaceDialog = true
                            isSaving = false
                        } else if (!GeofenceUtils.isWithinOuterGeofence(position.lat, position.lng)) {
                            // Coarse hard reject, ahead of the real geofence flow -- see
                            // GeofenceUtils.isWithinOuterGeofence's own comment. No SAVE ANYWAY,
                            // no pendingRecord, no soft warning: this location isn't remotely
                            // Cook Inlet.
                            showOutsideOuterGeofenceDialog = true
                            isSaving = false
                        } else {
                            when (GeofenceUtils.isWhalePositionVerified(position.lat, position.lng, position.uncertaintyRadiusMeters)) {
                                true -> finishWith(position, passed = true)
                                false -> finishWith(position, passed = false)
                                null -> {
                                    // Only reachable once the buffer check found no well-sourced
                                    // data near this point at all -- the online fallback never
                                    // runs, and this loading state never shows, on a normal
                                    // (resolved) submit.
                                    isCheckingCoastlineFallback = true
                                    val validByChannel = GeofenceUtils.isWithinCoastlineChannelFallback(position.lat, position.lng)
                                    isCheckingCoastlineFallback = false
                                    finishWith(position, passed = validByChannel)
                                }
                            }
                        }
                    }
                },
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.8f))
            ) {
                Text("← DONE", color = Color.Yellow, fontSize = 18.sp, fontWeight = FontWeight.Black)
            }

            Button(
                onClick = onRetakeClick,
                colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.8f))
            ) {
                Text("RETAKE ↻", color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Bold)
            }
        }

        // --- 3. LARGE DIRECTION ARROWS OVERLAID ON PHOTO ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 60.dp, bottom = 140.dp)
        ) {
            DirectionArrowButton(
                direction = PodDirection.AWAY,
                isSelected = selectedDirection == PodDirection.AWAY,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .size(width = 120.dp, height = 90.dp),
                onClick = { selectedDirection = PodDirection.AWAY }
            )

            DirectionArrowButton(
                direction = PodDirection.LEFT,
                isSelected = selectedDirection == PodDirection.LEFT,
                modifier = Modifier
                    .align(Alignment.CenterStart)
                    .padding(start = 24.dp)
                    .size(width = 110.dp, height = 110.dp),
                onClick = { selectedDirection = PodDirection.LEFT }
            )

            DirectionArrowButton(
                direction = PodDirection.RIGHT,
                isSelected = selectedDirection == PodDirection.RIGHT,
                modifier = Modifier
                    .align(Alignment.CenterEnd)
                    .padding(end = 24.dp)
                    .size(width = 110.dp, height = 110.dp),
                onClick = { selectedDirection = PodDirection.RIGHT }
            )
        }

        // --- 4. HEADING/DISTANCE + STACKED BOTTOM COUNTERS ROW ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // This screen doesn't go through AppBackground, so -- like ManualLoggingScreen --
                // it has to clear the system gesture/nav bar itself; App.kt's showPresenceBanner
                // excludes PHOTO_LOGGING outright, so there's no banner inset to add here.
                .navigationBarsPadding()
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                HeadingDistanceButton(
                    heading = headingEstimate,
                    distance = distanceBucket,
                    isAerial = isAerial,
                    originLat = observerLat,
                    originLng = observerLng,
                    altitudeMeters = observerAltitude,
                    region = region,
                    onConfirm = { h, d ->
                        headingEstimate = h
                        distanceBucket = d
                    }
                )
            }

            WhaleCountRow(
                whiteCount = whiteCount,
                onWhiteIncrement = { whiteCount++ },
                onWhiteDecrement = { if (whiteCount > 0) whiteCount-- },
                greyCount = greyCount,
                onGreyIncrement = { greyCount++ },
                onGreyDecrement = { if (greyCount > 0) greyCount-- },
                unknownCount = unknownCount,
                onUnknownIncrement = { unknownCount++ },
                onUnknownDecrement = { if (unknownCount > 0) unknownCount-- },
                calfCount = calfCount,
                onCalfIncrement = { calfCount++ },
                onCalfDecrement = { if (calfCount > 0) calfCount-- },
                modifier = Modifier.padding(top = 8.dp)
            )
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
                        text = "The estimated whale position (${record.whaleLat}, ${record.whaleLng}) falls outside the primary observation sightline for ${region.name}.\n\nDo you still want to log this sighting?"
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

        // --- CANNOT PLACE SIGHTING DIALOG ---
        // No SAVE ANYWAY here, deliberately -- see showCannotPlaceDialog's own declaration for
        // why: the only coordinates available in this failure case are the observer's raw GPS
        // fix, and saving those as the whale position would store exactly what this redesign
        // exists to stop storing.
        if (showCannotPlaceDialog) {
            AlertDialog(
                onDismissRequest = { showCannotPlaceDialog = false },
                title = {
                    Text(text = "Can't Place This Sighting", fontWeight = FontWeight.Bold)
                },
                text = {
                    val photoWarning = if (!capturedPhotoPath.isNullOrEmpty()) {
                        " Your photo will not carry over to Manual Logging and will be discarded."
                    } else {
                        ""
                    }
                    Text(
                        text = "No direction/distance was given, and this location isn't close enough to known water to estimate one automatically.\n\nUse Manual Logging to drop a pin at the sighting location instead.$photoWarning"
                    )
                },
                confirmButton = {
                    Button(
                        onClick = {
                            showCannotPlaceDialog = false
                            onNavigateToManualLogging()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800))
                    ) {
                        Text("GO TO MANUAL LOGGING", color = Color.Black, fontWeight = FontWeight.Black)
                    }
                },
                dismissButton = {
                    TextButton(onClick = { showCannotPlaceDialog = false }) {
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
 * Draws prominent directional arrows that display as an OUTLINE
 * until tapped, at which point they turn FILLED in solid yellow.
 */
@Composable
internal fun DirectionArrowButton(
    direction: PodDirection,
    isSelected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Canvas(
        modifier = modifier.clickable { onClick() }
    ) {
        val w = size.width
        val h = size.height
        val path = Path()

        when (direction) {
            PodDirection.AWAY -> { // Up Arrow
                path.moveTo(w * 0.5f, 0f)
                path.lineTo(w, h * 0.5f)
                path.lineTo(w * 0.7f, h * 0.5f)
                path.lineTo(w * 0.7f, h)
                path.lineTo(w * 0.3f, h)
                path.lineTo(w * 0.3f, h * 0.5f)
                path.lineTo(0f, h * 0.5f)
                path.close()
            }
            PodDirection.LEFT -> { // Left Arrow
                path.moveTo(0f, h * 0.5f)
                path.lineTo(w * 0.5f, 0f)
                path.lineTo(w * 0.5f, h * 0.3f)
                path.lineTo(w, h * 0.3f)
                path.lineTo(w, h * 0.7f)
                path.lineTo(w * 0.5f, h * 0.7f)
                path.lineTo(w * 0.5f, h)
                path.close()
            }
            PodDirection.RIGHT -> { // Right Arrow
                path.moveTo(w, h * 0.5f)
                path.lineTo(w * 0.5f, 0f)
                path.lineTo(w * 0.5f, h * 0.3f)
                path.lineTo(0f, h * 0.3f)
                path.lineTo(0f, h * 0.7f)
                path.lineTo(w * 0.5f, h * 0.7f)
                path.lineTo(w * 0.5f, h)
                path.close()
            }
            else -> {}
        }

        if (isSelected) {
            // SOLID FILLED when selected
            drawPath(path = path, color = Color.Yellow, style = Fill)
            drawPath(path = path, color = Color.Black, style = Stroke(width = 3.dp.toPx()))
        } else {
            // OUTLINED ONLY until pressed
            drawPath(path = path, color = Color.Yellow, style = Stroke(width = 5.dp.toPx()))
        }
    }
}
