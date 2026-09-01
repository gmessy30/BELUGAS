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

@Composable
fun LoggingScreen(
    capturedPhotoPath: String?,
    storage: LocalFileStorage,
    locationService: LocationService,
    region: RegionConfig = Regions.COOK_INLET,
    onDoneClick: () -> Unit,
    onRetakeClick: () -> Unit
) {
    var selectedDirection by remember { mutableStateOf(PodDirection.NONE) }
    var whiteCount by remember { mutableStateOf(0) }
    var greyCount by remember { mutableStateOf(0) }
    var calfCount by remember { mutableStateOf(0) }
    var unknownCount by remember { mutableStateOf(0) }

    var showGeofenceWarning by remember { mutableStateOf(false) }
    var showZeroCountWarning by remember { mutableStateOf(false) }
    var pendingRecord by remember { mutableStateOf<SightingRecord?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    // Only true while the online coastline-channel fallback (GeofenceUtils.
    // isWithinCoastlineChannelFallback) is running, i.e. only after the synchronous
    // isWithin3DFunnel check has already failed -- never shown on a normal submit.
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

                        val record = SightingRecord(
                            lat = currentLat,
                            lng = currentLng,
                            heading = selectedDirection.name.takeIf { selectedDirection != PodDirection.NONE } ?: "NONE",
                            countWhites = whiteCount,
                            countGreys = greyCount,
                            countCalves = calfCount,
                            countUnknown = unknownCount,
                            observedAtEpochMs = currentTimeMillis(),
                            observerType = ObserverType.SELF.name,
                            headingDegrees = headingEstimate?.degrees,
                            headingSource = headingEstimate?.source?.name,
                            headingAccuracyDegrees = headingEstimate?.accuracyDegrees,
                            distanceBucket = distanceBucket?.name,
                            // Uses the fresh GPS altitude fetched above rather than the picker's
                            // boot-time snapshot, in case the observer's altitude changed since.
                            distanceRadiusMeters = distanceBucket?.radiusMeters(currentAlt > 100.0)
                        )

                        if (GeofenceUtils.isWithin3DFunnel(currentLat, currentLng, currentAlt, region)) {
                            saveAndFinish(record.copy(isGeofenceVerified = true))
                        } else {
                            // Only reachable once the synchronous check has already rejected
                            // the point -- the online fallback never runs, and this loading
                            // state never shows, on a normal (accepted) submit.
                            isCheckingCoastlineFallback = true
                            val validByChannel = GeofenceUtils.isWithinCoastlineChannelFallback(currentLat, currentLng)
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

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically
            ) {
            // 1. Far Left (Furthest from thumb)
            WhaleCounterTile(
                label = "UNKNOWN",
                count = unknownCount,
                onIncrement = { unknownCount++ },
                onDecrement = { if (unknownCount > 0) unknownCount-- }
            )

            // 2. Middle Left
            WhaleCounterTile(
                label = "GREYS",
                count = greyCount,
                onIncrement = { greyCount++ },
                onDecrement = { if (greyCount > 0) greyCount-- }
            )

            // 3. Middle Right (Adjacent to Whites)
            WhaleCounterTile(
                label = "CALVES",
                count = calfCount,
                onIncrement = { calfCount++ },
                onDecrement = { if (calfCount > 0) calfCount-- }
            )

            // 4. Far Right (Closest to Right Thumb)
            WhaleCounterTile(
                label = "WHITES",
                count = whiteCount,
                onIncrement = { whiteCount++ },
                onDecrement = { if (whiteCount > 0) whiteCount-- }
            )
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
                        text = "Your GPS location (${record.lat}, ${record.lng}) falls outside the primary observation sightline for ${region.name}.\n\nDo you still want to log this sighting?"
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

/**
 * Individual Counter Block along the bottom row with slots for custom artwork.
 */
@Composable
internal fun WhaleCounterTile(
    label: String,
    count: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier
            .border(1.dp, Color.White.copy(alpha = 0.4f), RoundedCornerShape(10.dp))
            .background(Color.Black.copy(alpha = 0.65f), shape = RoundedCornerShape(10.dp))
            .padding(vertical = 6.dp, horizontal = 8.dp)
    ) {
        // --- 1. PLUS (+) BUTTON ON TOP ---
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 30.dp)
                .background(Color(0xFFFF9800), shape = RoundedCornerShape(4.dp))
                .clickable { onIncrement() },
            contentAlignment = Alignment.Center
        ) {
            Text("+", color = Color.Black, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }

        Spacer(modifier = Modifier.height(2.dp))

        // --- 2. LABEL PRINT (UNKNOWN / CALVES / GREYS / WHITES) ---
        Text(
            text = label,
            color = Color.White,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        // --- 3. COUNT NUMBER (0, 1, 2, 3...) ---
        Text(
            text = "$count",
            color = Color.Yellow,
            fontSize = 20.sp,
            fontWeight = FontWeight.Black
        )

        Spacer(modifier = Modifier.height(2.dp))

        // --- 4. MINUS (-) BUTTON ON BOTTOM ---
        Box(
            modifier = Modifier
                .size(width = 46.dp, height = 30.dp)
                .background(Color.DarkGray, shape = RoundedCornerShape(4.dp))
                .clickable { onDecrement() },
            contentAlignment = Alignment.Center
        ) {
            Text("-", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Black)
        }
    }
}
