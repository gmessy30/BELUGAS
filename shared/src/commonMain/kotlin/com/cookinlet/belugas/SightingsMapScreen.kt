package com.cookinlet.belugas

import androidx.compose.foundation.background
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
import com.cookinlet.belugas.db.SightingEntity
import org.maplibre.compose.map.MaplibreMap
import org.maplibre.compose.camera.*
import org.maplibre.compose.style.BaseStyle
import org.maplibre.spatialk.geojson.Position
import org.maplibre.compose.layers.SymbolLayer
import org.maplibre.compose.layers.CircleLayer
import org.maplibre.compose.sources.rememberGeoJsonSource
import org.maplibre.compose.sources.GeoJsonData
import org.maplibre.spatialk.geojson.Point
import kotlinx.serialization.json.JsonPrimitive
import org.maplibre.compose.expressions.dsl.*
import org.maplibre.compose.expressions.value.StringValue
import androidx.compose.ui.unit.em
import kotlinx.coroutines.delay
import androidx.compose.animation.*
import androidx.compose.foundation.shape.CircleShape

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SightingsMapScreen(
    localSightings: List<SightingEntity>,
    remoteSightings: List<SightingRecord>,
    isLoading: Boolean,
    region: RegionConfig = Regions.COOK_INLET,
    currentAltitude: Double,
    onCloseMap: () -> Unit
) {
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
        
        remoteSightings.forEach { s ->
            combined.add(SightingDisplayModel(
                lat = s.lat, lng = s.lng, timestamp = s.observedAtEpochMs ?: 0L,
                total = s.countWhites + s.countGreys + s.countCalves + s.countUnknown,
                isLocal = false, heading = s.heading ?: "NONE"
            ))
        }
        combined.sortedBy { it.timestamp }
    }

    // 2. Playback State Sanitization
    val minTime = remember(allSightings) {
        allSightings.minOfOrNull { it.timestamp } ?: currentTimeMillis()
    }
    val maxTime = remember(allSightings) {
        val last = allSightings.maxOfOrNull { it.timestamp } ?: currentTimeMillis()
        if (last <= minTime) minTime + 1000L else last
    }
    
    var playbackTimeMs by remember(allSightings) { mutableLongStateOf(maxTime) }
    var isPlaying by remember { mutableStateOf(false) }

    // Playback visibility toggle (Default: OFF)
    var isPlaybackVisible by remember { mutableStateOf(false) }

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
    LaunchedEffect(isPlaying, isPlaybackVisible, minTime, maxTime, speedMultiplier) {
        if (isPlaying && isPlaybackVisible) {
            val baseStepMs = 60_000L // 1 minute per tick at 1x
            val effectiveStepMs = baseStepMs * speedMultiplier
            
            while (isPlaying && isPlaybackVisible) {
                delay(100) 
                if (playbackTimeMs >= maxTime) {
                    isPlaying = false
                } else {
                    playbackTimeMs = (playbackTimeMs + effectiveStepMs).coerceAtMost(maxTime)
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
                            "source": "${if (s.isLocal) "Local" else "Supabase"}"
                          }
                        }
                        """.trimIndent()
                    }
                    """{ "type": "FeatureCollection", "features": [ ${features.joinToString(",")} ] }"""
                }

                val source = rememberGeoJsonSource(data = GeoJsonData.JsonString(geoJsonString))

                // Circle Layer as a reliable fallback (always visible)
                CircleLayer(
                    id = "sightings-circles",
                    source = source,
                    color = const(Color.Yellow),
                    radius = const(10.dp),
                    strokeColor = const(Color.Black),
                    strokeWidth = const(2.dp),
                    opacity = feature.get("alpha").cast()
                )

                // Label Layer for text
                SymbolLayer(
                    id = "sightings-labels",
                    source = source,
                    textField = format(span(feature.get("title").cast<StringValue>())),
                    textColor = const(Color.Black),
                    textHaloColor = const(Color.White),
                    textHaloWidth = const(2.dp),
                    textSize = const(11.sp),
                    textOffset = offset(0.em, 2.em),
                    textOpacity = feature.get("alpha").cast()
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
                    playbackTimeMs = minTime // Start from the beginning
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
                    // Header Row: Active Date + Close Button
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

                    Spacer(modifier = Modifier.height(4.dp))

                    // Scrubber Row: Play/Pause + Slider
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Button(
                            onClick = { isPlaying = !isPlaying },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                            modifier = Modifier.height(36.dp).padding(end = 8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp)
                        ) {
                            Text(if (isPlaying) "⏸ PAUSE" else "▶ PLAY", color = Color.White, fontSize = 11.sp)
                        }

                        if (maxTime > minTime) {
                            Slider(
                                value = playbackTimeMs.toFloat(),
                                onValueChange = { 
                                    playbackTimeMs = it.toLong()
                                    isPlaying = false 
                                },
                                valueRange = minTime.toFloat()..maxTime.toFloat(),
                                modifier = Modifier.weight(1f),
                                colors = SliderDefaults.colors(
                                    thumbColor = Color.Yellow,
                                    activeTrackColor = Color.Yellow,
                                    inactiveTrackColor = Color.DarkGray
                                )
                            )
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
