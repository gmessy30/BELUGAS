package com.cookinlet.belugas

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.round
import kotlin.math.sin
import kotlinx.coroutines.launch
import androidx.compose.foundation.Canvas
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.Path
import org.maplibre.compose.map.GestureOptions
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.map.OrnamentOptions
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
    // is no reliable observer vantage point in this flow to be relative to; see BearingDial's
    // doc comment). Optional, null = not recorded, matching travelBearingDegrees being optional
    // on SightingRecord.
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
    // Only true while the online coastline-channel fallback (GeofenceUtils.
    // isWithinCoastlineChannelFallback) is running, i.e. only when the buffer-based whale-
    // position check (GeofenceUtils.isWhalePositionVerified) found no well-sourced data near
    // the point at all -- never shown on a normal (resolved) submit.
    var isCheckingCoastlineFallback by remember { mutableStateOf(false) }

    // Measured height of the bottom controls panel (Row A + WhaleCountRow) -- read via
    // onGloballyPositioned on that Column below. RECENTER anchors above it, and the map's
    // ornamentOptions padding pushes the native logo/attribution up by the same amount, instead
    // of both guessing at a stack height that keeps changing as the panel's contents change.
    var bottomPanelHeightPx by remember { mutableStateOf(0) }
    val bottomPanelHeightDp = with(LocalDensity.current) { bottomPanelHeightPx.toDp() }

    val scope = rememberCoroutineScope()

    val cameraState = rememberCameraState(
        firstPosition = CameraPosition(
            target = Position(longitude = sightingLng, latitude = sightingLat),
            // Wide Cook Inlet overview, not a tight Kenai crop -- see COOK_INLET_OVERVIEW_ZOOM's
            // own comment. Only matters until the GPS recenter below lands (or fails/is denied,
            // in which case this is what stays on screen).
            zoom = COOK_INLET_OVERVIEW_ZOOM
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
                // Locked north-up here only (getMapOptions() itself is shared with
                // SightingsMapScreen/SubscriptionsScreen, which keep free rotation) -- the map
                // rotated on this screen only because nobody had disabled it, not by design, and
                // north-up is what BearingDial's fixed ring and drag pointer need to stay
                // meaningful without counter-rotating against the camera bearing.
                //
                // Ornaments, also local to this screen: only the scale bar (top-start) is
                // disabled -- it renders right behind the status bar/clock at the top edge,
                // pure clutter with no reading a fixed-zoom overview needs. Compass is left at
                // its default (top-end): confirmed on-device it auto-fades to invisible once the
                // map settles north-up, which this screen always is (see gestureOptions below),
                // so it never actually competes with MENU/RECENTER for that corner. Logo and
                // attribution are also left at default -- confirmed on-device they render in the
                // clear gap below the whale row, not under it, so no padding override is needed
                // (an earlier attempt to push them up via a measured-panel-height padding turned
                // out to shove them behind the RECENTER/SET DATE-TIME stack instead -- worse, not
                // better -- so that approach was dropped).
                options = getMapOptions().copy(
                    gestureOptions = GestureOptions.RotationLocked,
                    ornamentOptions = OrnamentOptions.AllEnabled.copy(isScaleBarEnabled = false)
                )
            )
        }

        // 2. FIXED CENTER TARGET MARKER -- BearingDial fuses the exact-location marker and the
        // travel-direction control into one element (see its own doc comment), replacing what
        // used to be a standalone pin here.
        BearingDial(
            bearingDegrees = travelBearingDegrees,
            onBearingChange = { degrees -> travelBearingDegrees = degrees },
            modifier = Modifier.align(Alignment.Center)
        )

        // 2b. LIVE COORDINATE READOUT -- surfaces the exact target coordinates a submit would
        // use (cameraState.position.target, same source as the SUBMIT button below), so a
        // geofence-rejected location can be read directly off the screen and reported precisely
        // instead of from memory. Top-center, between SUBMIT and MENU -- top-end used to crowd
        // that corner against MENU/SET DATE-TIME/RECENTER; top-center is empty (BearingDial owns
        // screen-center, not top-center).
        Text(
            text = "${formatCoord(cameraState.position.target.latitude)}, ${formatCoord(cameraState.position.target.longitude)}",
            color = Color.White,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 16.dp)
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

                        // There's no observer position on this screen to measure a distance
                        // from, so nothing is asked of the user and nothing is persisted for
                        // uncertaintyRadiusMeters/uncertaintyBucket below -- both are nullable
                        // columns and every consumer (SightingsMapScreen's circle rendering, the
                        // server-side zone/banner RPCs) already treats a null radius as "not
                        // reported" rather than assuming a value. isWhalePositionVerified below
                        // still needs *some* search buffer to check the pin against real
                        // whale-presence data, though, so it gets a fixed MEDIUM default -- local
                        // to this check only, never shown to the user, never saved to the record.
                        val geofenceCheckRadiusMeters = DistanceBucket.MEDIUM.radiusMeters(isAerial = false)

                        // This device's own persistent id, sent so the server can compute
                        // observerTier at insert -- never read back by the app (anon has no
                        // SELECT grant on sightings.subscriber_id at all, see
                        // 20260903010000_add_observer_tier_system.sql).
                        val subscriberId = appPreferences.getOrCreateSubscriberId()

                        val record = SightingRecord(
                            whaleLat = targetCenter.latitude,
                            whaleLng = targetCenter.longitude,
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

                        when (GeofenceUtils.isWhalePositionVerified(targetCenter.latitude, targetCenter.longitude, geofenceCheckRadiusMeters)) {
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

        // --- 3b. RECENTER (RESET VIEW) BUTTON ---
        // View reset only -- no GPS refetch. This screen marks where the whales were, not the
        // observer's own position, so there's no reason for RECENTER to go find the observer;
        // it just puts the fixed Cook Inlet overview (COOK_INLET_OVERVIEW_ZOOM) back under the
        // pin at Regions.COOK_INLET's own centre, same as the initial camera position above.
        Button(
            onClick = {
                cameraState.position = CameraPosition(
                    target = Position(longitude = region.defaultCenterLng, latitude = region.defaultCenterLat),
                    zoom = COOK_INLET_OVERVIEW_ZOOM
                )
            },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                // Anchored to the controls panel's own measured height (bottomPanelHeightDp),
                // not a hardcoded offset -- see that state's own comment. This stack height has
                // changed twice already; a fixed guess breaks again the next time it does.
                .padding(end = 16.dp, bottom = bottomPanelHeightDp + 16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.85f)),
            shape = RoundedCornerShape(8.dp),
            border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
        ) {
            Text("📍 RECENTER", color = Color.Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
        }

        // --- 4. FLOATING TRANSPARENT CONTROLS PANEL ---
        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                // This screen doesn't go through AppBackground, so it has to clear the system
                // gesture/nav bar itself -- without this, WhaleCountRow's "-" row (below the
                // rest of this stack) ends up under the gesture bar on gesture-nav devices.
                // No extra bottom cushion beyond that real inset: MapLibre's logo/attribution
                // (confirmed on-device to render clear of this panel at their default position)
                // leave dead space below the whale row otherwise, and closing that gap is the
                // point of anchoring RECENTER to this panel's measured height in the first place.
                .navigationBarsPadding()
                .onGloballyPositioned { coordinates -> bottomPanelHeightPx = coordinates.size.height }
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

            // Row B: Whale count buttons
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
                modifier = Modifier.padding(top = 4.dp)
            )
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

// BEARING_DIAL_* sizing gives the ring a comfortable presence over the map without hiding much
// shoreline around it.
private val BEARING_DIAL_OUTER_RADIUS = 65.dp
private val BEARING_DIAL_RING_THICKNESS = 14.dp
private val BEARING_DIAL_PIN_SIZE = 30.dp
// Black outline width added to each side of the ring/crosshair/needle strokes -- see the Canvas
// block's own comment for why (the pin can land on anything from pale channel to dark shoreline
// fill).
private val BEARING_DIAL_OUTLINE_WIDTH = 2.dp

// Fixed Cook Inlet overview -- same centre as Regions.COOK_INLET (that constant is the region
// definition geofencing/other screens read; left untouched here, this is only a map-framing
// choice). Wide enough that Kasilof and Anchorage both stay in frame from that centre, even
// though the centre itself sits close to Kenai rather than at the true midpoint between them:
// Kasilof is only ~0.19 deg lat / 0.01 deg lng from the centre, Anchorage ~0.66 deg lat / 1.36
// deg lng, so the frame has to extend as far as the Anchorage side in both directions (with ~12%
// margin so neither town sits right at the edge) -- lat span ~1.49 deg, lng span ~3.04 deg. At
// this latitude (~60.55, cos ~0.492) that's roughly 166km each way. On an assumed ~880x400dp
// landscape viewport that puts the binding (vertical/lat) constraint at zoom ~7.5; used here
// unrounded-but-close as 7.4. Confirm against a real landscape screenshot and nudge if either
// town clips off-frame on an actual device.
private const val COOK_INLET_OVERVIEW_ZOOM = 7.4

/**
 * The teardrop map-pin shape, extracted from what used to be ManualLoggingScreen's standalone
 * center marker -- now reused at a smaller scale as BearingDial's decorative north point.
 */
@Composable
private fun PinGraphic(modifier: Modifier = Modifier, color: Color = Color.Red) {
    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val path = Path().apply {
            moveTo(w * 0.5f, h)
            cubicTo(w * 0.2f, h * 0.7f, 0f, h * 0.5f, 0f, h * 0.35f)
            arcTo(
                rect = Rect(0f, 0f, w, h * 0.7f),
                startAngleDegrees = 180f,
                sweepAngleDegrees = 180f,
                forceMoveTo = false
            )
            cubicTo(w, h * 0.5f, w * 0.8f, h * 0.7f, w * 0.5f, h)
            close()
        }
        drawPath(path, color, style = Fill)
        drawCircle(Color.White, radius = w * 0.15f, center = Offset(w * 0.5f, h * 0.35f))
    }
}

/**
 * Fused location-marker + travel-direction control for ManualLoggingScreen's dropped pin,
 * replacing the old standalone pin AND the old floating CompassRose (which sat in the bottom
 * panel and, once the panel's stack grew, ended up visually overlapping this exact spot -- see
 * that redesign's own discussion). Centering this whole dial where the pin used to sit removes
 * the second element competing for space entirely, rather than repositioning it.
 *
 * A crosshair -- not the old teardrop tip -- now marks the exact whale position at the ring's
 * center: unlike a teardrop (whose "point" sits at the bottom of its bounding box, needing a
 * compensating vertical offset to align with the true target), a crosshair is symmetric around
 * its own center, so this needs no such offset. The teardrop pin graphic itself moves onto the
 * ring at the north position -- decorative in this control now, not a location marker.
 *
 * Not relative AWAY/LEFT/RIGHT arrows like LoggingScreen's: this screen has no reliable observer
 * vantage point to be relative to (a dropped pin can be placed from memory, panned to a spot
 * watched from elsewhere, or filed well after the fact), so a relative direction here would rest
 * on an unverifiable assumption about where the user is standing and which way they're facing.
 * Fixed (non-rotating) rather than tracking the map's camera bearing: ManualLoggingScreen locks
 * the map to north-up (see its MaplibreMap's gestureOptions) specifically so this ring's "up"
 * can always mean true north without reading cameraState.position.bearing.
 *
 * Continuous drag around (or anywhere within) the dial, free rotation with no visible snapping
 * while dragging, the pointer resting exactly where the user releases it -- never a jump, never
 * a shown degree or point name. Only the value actually written to travelBearingDegrees
 * (travelBearingSource = MANUAL, same fields the old CompassBearingButton used) gets rounded to
 * the nearest of 16 points, invisibly, at release -- see snapToNearest16Point. 16 rather than 8:
 * the existing manually-entered sightings were all recorded at 8-point resolution, so 16 is still
 * an exact superset of every value already on file (each old 8-point value is also a 16-point
 * value) while adding the intercardinals in between. A yellow needle from center to the ring
 * (same black-outline contrast treatment as the ring/crosshair) renders whenever a bearing is
 * set -- mid-drag it tracks the live (unsnapped) angle so the motion feels continuous, snapping
 * only at release; before any input it's simply absent, same as the un-set state before this
 * screen had any bearing control at all.
 */
@Composable
private fun BearingDial(
    bearingDegrees: Double?,
    onBearingChange: (Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val ringCenterlineRadius = BEARING_DIAL_OUTER_RADIUS - BEARING_DIAL_RING_THICKNESS / 2
    // Angle tracked while a drag is in progress, before release snaps it into
    // travelBearingDegrees -- lets the needle follow the finger continuously instead of jumping
    // only on commit.
    var liveDragDegrees by remember { mutableStateOf<Double?>(null) }
    val displayDegrees = liveDragDegrees ?: bearingDegrees

    Box(
        modifier = modifier
            .size(BEARING_DIAL_OUTER_RADIUS * 2)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { offset -> liveDragDegrees = bearingFromOffset(offset, size) },
                    onDragEnd = {
                        liveDragDegrees?.let { onBearingChange(snapToNearest16Point(it)) }
                        liveDragDegrees = null
                    },
                    onDragCancel = { liveDragDegrees = null },
                    onDrag = { change, _ -> liveDragDegrees = bearingFromOffset(change.position, size) }
                )
            },
        contentAlignment = Alignment.Center
    ) {
        Canvas(modifier = Modifier.matchParentSize()) {
            // Black-outline-then-white, same fix as WhaleCountControls' OutlinedText: the pin
            // can land anywhere from pale channel water to darker shoreline fill, and a plain
            // white stroke alone all but disappears against the pale end of that range. Drawing
            // a wider black stroke first, then the real white stroke on top at the original
            // width (both centered on the same radius/line), leaves a black edge on both sides
            // of the white -- readable regardless of what's underneath.
            val ringOutlineWidth = BEARING_DIAL_RING_THICKNESS + BEARING_DIAL_OUTLINE_WIDTH * 2
            drawCircle(
                color = Color.Black,
                radius = ringCenterlineRadius.toPx(),
                style = Stroke(width = ringOutlineWidth.toPx())
            )
            drawCircle(
                color = Color.White,
                radius = ringCenterlineRadius.toPx(),
                style = Stroke(width = BEARING_DIAL_RING_THICKNESS.toPx())
            )

            val crosshairLength = 9.dp.toPx()
            val strokeWidth = 2.dp.toPx()
            val outlineStrokeWidth = strokeWidth + BEARING_DIAL_OUTLINE_WIDTH.toPx() * 2
            val crosshairPoints = listOf(
                Offset(center.x - crosshairLength, center.y) to Offset(center.x + crosshairLength, center.y),
                Offset(center.x, center.y - crosshairLength) to Offset(center.x, center.y + crosshairLength)
            )
            crosshairPoints.forEach { (start, end) ->
                drawLine(Color.Black, start = start, end = end, strokeWidth = outlineStrokeWidth, cap = StrokeCap.Round)
            }
            crosshairPoints.forEach { (start, end) ->
                drawLine(Color.White, start = start, end = end, strokeWidth = strokeWidth, cap = StrokeCap.Round)
            }

            // The one visible indicator of which direction is actually selected -- absent when
            // displayDegrees is null (nothing picked yet), present and pointing at the live or
            // committed bearing otherwise. Yellow rather than white/black: needs to read as
            // distinct from the ring/crosshair, not just another outlined white line.
            displayDegrees?.let { degrees ->
                val radians = degrees * PI / 180.0
                val dirX = sin(radians).toFloat()
                val dirY = -cos(radians).toFloat()
                val needleEnd = Offset(
                    center.x + dirX * ringCenterlineRadius.toPx(),
                    center.y + dirY * ringCenterlineRadius.toPx()
                )
                val needleWidth = 4.dp.toPx()
                val needleOutlineWidth = needleWidth + BEARING_DIAL_OUTLINE_WIDTH.toPx() * 2
                drawLine(Color.Black, start = center, end = needleEnd, strokeWidth = needleOutlineWidth, cap = StrokeCap.Round)
                drawLine(Color.Yellow, start = center, end = needleEnd, strokeWidth = needleWidth, cap = StrokeCap.Round)
            }
        }

        PinGraphic(
            modifier = Modifier
                .align(Alignment.Center)
                .offset(y = -ringCenterlineRadius)
                .size(BEARING_DIAL_PIN_SIZE)
        )
    }
}

/**
 * Touch position (in BearingDial's own local pixel space, origin top-left) to a compass bearing
 * in degrees, 0..360 exclusive of 360 itself, 0 = north/up, clockwise. atan2(dx, -dy): dy is
 * negated because screen-space y grows downward while "up" (north) must map to 0 degrees.
 */
private fun bearingFromOffset(offset: Offset, size: IntSize): Double {
    val dx = (offset.x - size.width / 2f).toDouble()
    val dy = (offset.y - size.height / 2f).toDouble()
    val degrees = atan2(dx, -dy) * (180.0 / PI)
    return (degrees + 360.0) % 360.0
}

/** Nearest of 16 compass points (22.5 degree steps) -- see BearingDial's own doc comment for why. */
private fun snapToNearest16Point(degrees: Double): Double {
    return (round(degrees / 22.5) * 22.5) % 360.0
}
