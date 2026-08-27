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
    region: RegionConfig = Regions.COOK_INLET,
    onDoneClick: () -> Unit,
    onOpenMenuClick: () -> Unit
) {
    // Sighting Position State (Defaults safely to region center)
    var sightingLat by remember { mutableStateOf(region.defaultCenterLat) }
    var sightingLng by remember { mutableStateOf(region.defaultCenterLng) }
    var sightingAlt by remember { mutableStateOf(0.0) }

    // Pod Metadata State
    var selectedDirection by remember { mutableStateOf(PodDirection.NONE) }
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
    var pendingRecord by remember { mutableStateOf<SightingRecord?>(null) }
    var isSaving by remember { mutableStateOf(false) }
    var isRecentering by remember { mutableStateOf(false) }

    var headingEstimate by remember { mutableStateOf<HeadingEstimate?>(null) }
    var distanceBucket by remember { mutableStateOf<DistanceBucket?>(null) }
    val isAerial = sightingAlt > 100.0

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
                // Ensure we use the latest map center for the record
                val center = cameraState.position.target
                val updatedRecord = record.copy(
                    lat = center.latitude,
                    lng = center.longitude
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

                    // Use target map coordinates for the record
                    val targetCenter = cameraState.position.target
                    val record = SightingRecord(
                        lat = targetCenter.latitude,
                        lng = targetCenter.longitude,
                        heading = selectedDirection.name.takeIf { selectedDirection != PodDirection.NONE } ?: "NONE",
                        countWhites = whiteCount,
                        countGreys = greyCount,
                        countCalves = calfCount,
                        countUnknown = unknownCount,
                        observedAtEpochMs = selectedTimestampMs,
                        observerType = observerType.name,
                        headingDegrees = headingEstimate?.degrees,
                        headingSource = headingEstimate?.source?.name,
                        headingAccuracyDegrees = headingEstimate?.accuracyDegrees,
                        distanceBucket = distanceBucket?.name,
                        distanceRadiusMeters = distanceBucket?.radiusMeters(isAerial)
                    )

                    if (!GeofenceUtils.isWithin3DFunnel(targetCenter.latitude, targetCenter.longitude, sightingAlt, region)) {
                        pendingRecord = record
                        showGeofenceWarning = true
                        isSaving = false
                    } else {
                        saveAndFinish(record)
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

        // --- 3. DIRECTION ARROWS OVERLAID ON MAP ---
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = 60.dp, bottom = 210.dp)
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

            // Row A2: Heading & Distance
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center
            ) {
                val pinTarget = cameraState.position.target
                HeadingDistanceButton(
                    heading = headingEstimate,
                    distance = distanceBucket,
                    isAerial = isAerial,
                    originLat = pinTarget.latitude,
                    originLng = pinTarget.longitude,
                    altitudeMeters = sightingAlt,
                    region = region,
                    onConfirm = { h, d ->
                        headingEstimate = h
                        distanceBucket = d
                    }
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
                        text = "Your selected location (${record.lat}, ${record.lng}) falls outside the primary observation sightline for ${region.name}.\n\nDo you still want to log this sighting?"
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
