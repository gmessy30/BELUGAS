package com.cookinlet.belugas

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
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

private const val THIRTY_DAYS_MS = 30L * 24 * 60 * 60 * 1000

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportScreen(onBack: () -> Unit) {
    var selectedRegion by remember { mutableStateOf(Regions.COOK_INLET) }
    var zones by remember { mutableStateOf<List<ZoneRecord>>(emptyList()) }
    // null = every zone in the region (no polygon narrowing, just the region bounding box).
    var selectedZoneSlug by remember { mutableStateOf<String?>(null) }
    var includePhotos by remember { mutableStateOf(true) }

    var startDateMs by remember { mutableStateOf(currentTimeMillis() - THIRTY_DAYS_MS) }
    var endDateMs by remember { mutableStateOf(currentTimeMillis()) }
    var showDateRangePicker by remember { mutableStateOf(false) }

    var isExporting by remember { mutableStateOf(false) }
    var photoProgress by remember { mutableStateOf<Pair<Int, Int>?>(null) }
    var exportError by remember { mutableStateOf<String?>(null) }

    val scope = rememberCoroutineScope()
    val fileSharer = rememberFileSharer()

    // Re-fetch this region's zones whenever it changes, and drop any zone selection that
    // belonged to the previous region.
    LaunchedEffect(selectedRegion) {
        selectedZoneSlug = null
        zones = SupabaseApi.getZones(selectedRegion.id)
    }

    fun runExport() {
        if (isExporting) return
        scope.launch {
            isExporting = true
            exportError = null
            photoProgress = null
            try {
                val records = SupabaseApi.exportSightings(
                    startMs = startDateMs,
                    endMs = endDateMs,
                    region = selectedRegion,
                    zoneSlug = selectedZoneSlug
                )
                if (records.isEmpty()) {
                    exportError = "No sightings match these filters."
                } else {
                    val artifact = ExportBuilder.build(records, includePhotos) { done, total ->
                        photoProgress = done to total
                    }
                    when (artifact) {
                        is ExportArtifact.Csv ->
                            fileSharer.share(artifact.fileName, artifact.mimeType, artifact.content.encodeToByteArray())
                        is ExportArtifact.Zip ->
                            fileSharer.share(artifact.fileName, artifact.mimeType, artifact.bytes)
                    }
                }
            } catch (e: Exception) {
                // Postgrest exceptions stuff the full request (including the anon key in the
                // Authorization header) into e.message after a "\nURL:" marker -- fine to log,
                // not fine to put on screen, so only the human-readable part before it is shown.
                println("EXPORT_ERROR: [${e::class.simpleName}] ${e.message}")
                exportError = "Export failed: ${e.message?.substringBefore("\nURL:") ?: e::class.simpleName}"
            } finally {
                isExporting = false
                photoProgress = null
            }
        }
    }

    AppBackground {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("EXPORT DATA", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                TextButton(onClick = onBack) {
                    Text("← BACK", color = Color.Yellow, fontWeight = FontWeight.Bold)
                }
            }

            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 8.dp)
            ) {
                ExportSection(title = "REGION") {
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.horizontalScroll(rememberScrollState())
                    ) {
                        Regions.ALL.forEach { region ->
                            FilterChip(
                                selected = selectedRegion.id == region.id,
                                onClick = { selectedRegion = region },
                                label = { Text(region.name, fontSize = 12.sp) },
                                colors = exportChipColors()
                            )
                        }
                    }
                }

                ExportSection(title = "ZONE") {
                    if (zones.isEmpty()) {
                        Text(
                            "No named zones for this region — exporting across the whole region.",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 12.sp
                        )
                    } else {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.horizontalScroll(rememberScrollState())
                        ) {
                            FilterChip(
                                selected = selectedZoneSlug == null,
                                onClick = { selectedZoneSlug = null },
                                label = { Text("ALL ZONES", fontSize = 12.sp) },
                                colors = exportChipColors()
                            )
                            zones.forEach { zone ->
                                FilterChip(
                                    selected = selectedZoneSlug == zone.slug,
                                    onClick = { selectedZoneSlug = zone.slug },
                                    label = { Text(zone.name, fontSize = 12.sp) },
                                    colors = exportChipColors()
                                )
                            }
                        }
                    }
                }

                ExportSection(title = "DATE RANGE") {
                    Text(
                        text = "${formatDateLabel(startDateMs)} — ${formatDateLabel(endDateMs)}",
                        color = Color.White,
                        fontSize = 13.sp
                    )
                    Spacer(Modifier.height(8.dp))
                    Button(
                        onClick = { showDateRangePicker = true },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Black.copy(alpha = 0.75f)),
                        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.3f))
                    ) {
                        Text("📅 SET DATE RANGE", color = Color.Yellow, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }

                ExportSection(title = "PHOTOS") {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Include photos", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text(
                                "Bundles a photos/ folder with the CSV. Turn off for a faster, lighter download.",
                                color = Color.White.copy(alpha = 0.7f),
                                fontSize = 11.sp
                            )
                        }
                        Switch(
                            checked = includePhotos,
                            onCheckedChange = { includePhotos = it },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color.Yellow,
                                checkedTrackColor = Color(0xFFFF9800)
                            )
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Button(
                    onClick = { runExport() },
                    enabled = !isExporting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isExporting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.Black,
                            strokeWidth = 2.dp
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            photoProgress?.let { (done, total) -> "DOWNLOADING PHOTOS $done/$total..." }
                                ?: "PREPARING EXPORT...",
                            color = Color.Black,
                            fontWeight = FontWeight.Black
                        )
                    } else {
                        Text("⬇ DOWNLOAD", color = Color.Black, fontSize = 16.sp, fontWeight = FontWeight.Black)
                    }
                }

                exportError?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = Color(0xFFFF5252), fontSize = 12.sp)
                }

                Spacer(Modifier.height(24.dp))
            }
        }

        if (showDateRangePicker) {
            val rangeState = rememberDateRangePickerState(
                initialSelectedStartDateMillis = startDateMs,
                initialSelectedEndDateMillis = endDateMs
            )
            Dialog(
                onDismissRequest = { showDateRangePicker = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column {
                        DateRangePicker(state = rangeState, modifier = Modifier.weight(1f))
                        Row(
                            horizontalArrangement = Arrangement.End,
                            modifier = Modifier.fillMaxWidth().padding(16.dp)
                        ) {
                            TextButton(onClick = { showDateRangePicker = false }) { Text("CANCEL") }
                            Spacer(Modifier.width(8.dp))
                            TextButton(onClick = {
                                rangeState.selectedStartDateMillis?.let { startDateMs = it }
                                rangeState.selectedEndDateMillis?.let { endDateMs = it }
                                showDateRangePicker = false
                            }) { Text("OK") }
                        }
                    }
                }
            }
        }
    }
    }
}

@Composable
private fun exportChipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = Color.Yellow,
    selectedLabelColor = Color.Black,
    containerColor = Color.White.copy(alpha = 0.1f),
    labelColor = Color.White
)

@Composable
private fun ExportSection(title: String, content: @Composable ColumnScope.() -> Unit) {
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
