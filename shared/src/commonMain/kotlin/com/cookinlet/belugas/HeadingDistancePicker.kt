package com.cookinlet.belugas

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val COMPASS_POINTS = listOf(
    "N" to 0.0, "NE" to 45.0, "E" to 90.0, "SE" to 135.0,
    "S" to 180.0, "SW" to 225.0, "W" to 270.0, "NW" to 315.0
)

/**
 * Compact trigger button showing the current heading/distance selection (or a prompt if unset).
 * Tapping it opens [HeadingDistanceDialog].
 */
@Composable
fun HeadingDistanceButton(
    heading: HeadingEstimate?,
    distance: DistanceBucket?,
    isAerial: Boolean,
    originLat: Double,
    originLng: Double,
    altitudeMeters: Double,
    region: RegionConfig,
    modifier: Modifier = Modifier,
    onConfirm: (HeadingEstimate, DistanceBucket) -> Unit
) {
    var showDialog by remember { mutableStateOf(false) }

    val summary = if (heading != null && distance != null) {
        "🧭 ${heading.degrees.toInt()}° · ${distance.label.uppercase()}"
    } else {
        "🧭 SET HEADING & DISTANCE"
    }

    Button(
        onClick = { showDialog = true },
        modifier = modifier,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.75f)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
    ) {
        Text(summary, color = Color.Yellow, fontSize = 11.sp, fontWeight = FontWeight.Bold)
    }

    if (showDialog) {
        HeadingDistanceDialog(
            isAerial = isAerial,
            originLat = originLat,
            originLng = originLng,
            altitudeMeters = altitudeMeters,
            region = region,
            initialHeading = heading,
            initialDistance = distance,
            onDismiss = { showDialog = false },
            onConfirm = { h, d ->
                onConfirm(h, d)
                showDialog = false
            }
        )
    }
}

@Composable
private fun HeadingDistanceDialog(
    isAerial: Boolean,
    originLat: Double,
    originLng: Double,
    altitudeMeters: Double,
    region: RegionConfig,
    initialHeading: HeadingEstimate?,
    initialDistance: DistanceBucket?,
    onDismiss: () -> Unit,
    onConfirm: (HeadingEstimate, DistanceBucket) -> Unit
) {
    val compassService = rememberCompassService()

    var sensorReading by remember { mutableStateOf<HeadingEstimate?>(null) }
    var isSensing by remember { mutableStateOf(true) }
    var useManual by remember { mutableStateOf(initialHeading?.source == HeadingSource.MANUAL) }
    var manualDegrees by remember { mutableStateOf(initialHeading?.degrees ?: 0.0) }
    var selectedDistance by remember { mutableStateOf(initialDistance ?: DistanceBucket.MEDIUM) }

    LaunchedEffect(Unit) {
        val reading = compassService.getCurrentHeading()
        sensorReading = reading
        isSensing = false
        if (reading == null) useManual = true
    }

    val currentHeading = if (!useManual && sensorReading != null) {
        sensorReading!!
    } else {
        HeadingEstimate(manualDegrees, HeadingSource.MANUAL)
    }

    val radiusMeters = selectedDistance.radiusMeters(isAerial)
    val sectorOnWater = remember(currentHeading, selectedDistance) {
        sectorEndpointWithinGeofence(originLat, originLng, currentHeading, radiusMeters, altitudeMeters, region)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Heading & Distance", fontWeight = FontWeight.Bold) },
        text = {
            Column {
                when {
                    isSensing -> Text("📡 Reading compass…", color = Color.Gray, fontSize = 12.sp)
                    sensorReading != null -> Text(
                        "✓ Compass locked: ${sensorReading!!.degrees.toInt()}° " +
                            "(±${sensorReading!!.accuracyDegrees?.toInt() ?: "?"}°)",
                        color = Color(0xFFA5D6A7),
                        fontSize = 12.sp
                    )
                    else -> Text(
                        "⚠ Compass unavailable or uncalibrated — enter heading manually",
                        color = Color(0xFFFFCC80),
                        fontSize = 12.sp
                    )
                }

                Spacer(Modifier.height(8.dp))

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = useManual,
                        onCheckedChange = { useManual = it },
                        enabled = sensorReading != null
                    )
                    Text("Enter heading manually", fontSize = 12.sp)
                }

                if (useManual) {
                    Spacer(Modifier.height(8.dp))
                    // FlowRow instead of Row: the dialog isn't wide enough to fit all 8 compass
                    // chips on one line, and a plain Row neither wraps nor scrolls, so the
                    // overflowing chips (past SW) were laid out beyond the dialog's clipped
                    // bounds and unreachable. Wrapping onto a second line keeps every direction
                    // reliably visible and tappable without needing a scroll gesture at all.
                    FlowRow(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        COMPASS_POINTS.forEach { (label, deg) ->
                            FilterChip(
                                selected = manualDegrees == deg,
                                onClick = { manualDegrees = deg },
                                label = { Text(label, fontSize = 10.sp) },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Color.Yellow,
                                    selectedLabelColor = Color.Black
                                )
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("Fine-tune: ${manualDegrees.toInt()}°", fontSize = 11.sp, color = Color.Gray)
                    Slider(
                        value = manualDegrees.toFloat(),
                        onValueChange = { manualDegrees = it.toDouble() },
                        valueRange = 0f..359f
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("Distance", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 4.dp)
                ) {
                    DistanceBucket.entries.forEach { bucket ->
                        FilterChip(
                            selected = selectedDistance == bucket,
                            onClick = { selectedDistance = bucket },
                            label = { Text(bucket.rangeLabel(isAerial), fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00E5FF),
                                selectedLabelColor = Color.Black
                            )
                        )
                    }
                }

                if (!sectorOnWater) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠ This heading/distance points outside ${region.name}'s typical " +
                            "sightline — sector may fall on land.",
                        color = Color(0xFFFFCC80),
                        fontSize = 11.sp
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(currentHeading, selectedDistance) }) {
                Text("USE THIS", color = Color.Yellow, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("CANCEL") }
        }
    )
}
