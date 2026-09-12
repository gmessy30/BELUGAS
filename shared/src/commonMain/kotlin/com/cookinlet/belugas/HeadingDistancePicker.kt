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

// Also reused by ManualLoggingScreen's CompassBearingButton -- same 8 points, same degree
// values, no reason for a second lookup table.
val COMPASS_POINTS = listOf(
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
    // BUG FIX (item 47): both nullable now -- see HeadingDistanceDialog's own comments on
    // manualDegrees/selectedDistance for why a confirm can now genuinely carry a null heading
    // and/or a null distance.
    onConfirm: (HeadingEstimate?, DistanceBucket?) -> Unit
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
    // BUG FIX (item 47): HeadingEstimate? not HeadingEstimate -- see manualDegrees' own comment
    // for why a confirm can now genuinely carry a null heading (manual entry, never touched).
    onConfirm: (HeadingEstimate?, DistanceBucket?) -> Unit
) {
    val compassService = rememberCompassService()

    var sensorReading by remember { mutableStateOf<HeadingEstimate?>(null) }
    var isSensing by remember { mutableStateOf(true) }
    var useManual by remember { mutableStateOf(initialHeading?.source == HeadingSource.MANUAL) }
    // BUG FIX (item 47): was `initialHeading?.degrees ?: 0.0` -- on a genuinely fresh pick
    // (initialHeading null, manual entry active because the sensor is unavailable), 0.0 happens
    // to equal COMPASS_POINTS' own "N" value, so the N chip rendered as already selected and
    // tapping USE THIS without ever touching a chip or the slider committed "0°/North" as if it
    // had been deliberately chosen -- same shape of bug as selectedDistance's own MEDIUM default
    // below. Re-opening to adjust an ALREADY-confirmed heading still carries the real value over
    // correctly (that's what initialHeading?.degrees IS in that case); only the never-set case
    // now stays null.
    var manualDegrees by remember { mutableStateOf(initialHeading?.degrees) }
    // BUG FIX (item 47): was `initialDistance ?: DistanceBucket.MEDIUM` -- on a genuinely fresh
    // pick (initialDistance null), that silently seeded MEDIUM as if it had been chosen, and
    // tapping USE THIS without ever touching a distance chip committed it as fact. Re-opening to
    // adjust an ALREADY-confirmed distance still carries the real value over correctly (that's
    // what initialDistance IS in that case); only the never-set case now stays null through to
    // onConfirm, matching LoggingScreen's own launchSubmit gate (heading != null && bucket !=
    // null), which already routes a null bucket to the "Can't Place This Sighting" dialog exactly
    // like a null heading always has.
    var selectedDistance by remember { mutableStateOf(initialDistance) }

    LaunchedEffect(Unit) {
        val reading = compassService.getCurrentHeading()
        sensorReading = reading
        isSensing = false
        if (reading == null) useManual = true
    }

    // BUG FIX (item 47): nullable now -- manualDegrees can genuinely be null (see its own comment
    // above), so there may be no real current heading at all yet. onConfirm/HeadingDistanceButton
    // widened to HeadingEstimate? to carry that through; LoggingScreen's own launchSubmit gate
    // already treats a null heading as "can't place this sighting," same as it always has.
    val currentHeading: HeadingEstimate? = if (!useManual && sensorReading != null) {
        sensorReading
    } else {
        manualDegrees?.let { HeadingEstimate(it, HeadingSource.MANUAL) }
    }

    // All three null-guarded -- currentHeading/selectedDistance can genuinely be null now (see
    // their own comments above).
    val radiusMeters = selectedDistance?.radiusMeters(isAerial)
    val sectorOnWater = remember(currentHeading, selectedDistance) {
        if (currentHeading == null || radiusMeters == null) {
            true // nothing to warn about yet -- no heading/distance chosen means no sector to check
        } else {
            sectorEndpointWithinGeofence(originLat, originLng, currentHeading, radiusMeters, altitudeMeters, region)
        }
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
                    // Note: horizontalArrangement intentionally does NOT use the
                    // spacedBy(space, alignment) overload here (e.g. Alignment.CenterHorizontally)
                    // -- combined with FlowRow in this Compose Foundation version (1.10.4), that
                    // overload silently drops whichever chips overflow onto a wrapped line
                    // instead of wrapping them (confirmed on-device: the last chip just vanished
                    // from the tree entirely). Plain spacedBy keeps every chip visible.
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
                    // BUG FIX (item 47): label reads "Not set" rather than a fake "0°" when
                    // manualDegrees is genuinely null -- the Slider itself still needs SOME
                    // numeric thumb position to render at (0f, same as before), but that's purely
                    // a starting visual -- it doesn't itself commit manualDegrees to 0.0.
                    Text(
                        text = manualDegrees?.let { "Fine-tune: ${it.toInt()}°" } ?: "Fine-tune: not set",
                        fontSize = 11.sp,
                        color = Color.Gray
                    )
                    Slider(
                        value = (manualDegrees ?: 0.0).toFloat(),
                        onValueChange = { manualDegrees = it.toDouble() },
                        valueRange = 0f..359f
                    )
                }

                Spacer(Modifier.height(16.dp))
                Text("Distance", fontWeight = FontWeight.Bold, fontSize = 13.sp)
                // A wrapped second line here (previously via FlowRow) could get squeezed out of
                // the dialog's vertical space in landscape, where the dialog has much less
                // height to work with. Short comparator+distance labels (e.g. "~500m" instead
                // of "Medium (~500m)") keep all three chips narrow enough to always fit on one
                // line, and equal weight makes them share the row evenly regardless of the
                // dialog's width in either orientation.
                Row(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 4.dp)
                ) {
                    DistanceBucket.entries.forEach { bucket ->
                        FilterChip(
                            selected = selectedDistance == bucket,
                            onClick = { selectedDistance = bucket },
                            label = { Text(bucket.shortLabel(isAerial), fontSize = 10.sp) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF00E5FF),
                                selectedLabelColor = Color.Black
                            ),
                            modifier = Modifier.weight(1f)
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
