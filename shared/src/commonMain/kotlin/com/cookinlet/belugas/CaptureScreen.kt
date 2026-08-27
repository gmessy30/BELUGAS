package com.cookinlet.belugas

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import belugas.shared.generated.resources.Res
import belugas.shared.generated.resources.beluga_capture_sketch
import org.jetbrains.compose.resources.painterResource

@Composable
fun CaptureScreen(
    onPhotoCaptured: (String?) -> Unit,
    onOpenMenu: () -> Unit,
    storage: LocalFileStorage,
    currentAltitude: Double
) {
    var triggerSnapshot by remember { mutableStateOf(false) }
    val pendingCount by OfflineSightingRepository.pendingCount.collectAsState()
    val scope = rememberCoroutineScope()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // --- 1. LIVE CAMERA FEED & SNAPSHOT ENGINE ---
        CameraPreviewHost(
            modifier = Modifier.fillMaxSize(),
            takePhotoSignal = triggerSnapshot,
            onPhotoCaptured = { savedPath ->
                triggerSnapshot = false
                onPhotoCaptured(savedPath)
            }
        )

        // --- 2. SKETCHED RETICLE OVERLAY ---
        SketchedReticle(
            modifier = Modifier.align(Alignment.Center)
        )

        // --- 3. TOP CONTROL ROW: MENU BUTTON (LEFT) + SYNC BADGE (RIGHT) ---
        Column(
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = onOpenMenu,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray.copy(alpha = 0.7f))
                ) {
                    Text("≡ MENU", color = Color.White)
                }

                PendingSyncBadge(
                    count = pendingCount,
                    onClick = {
                        SyncEngine.processQueueInBackground(scope, storage)
                    }
                )
            }

            ObserverElevationTip(
                currentAltitudeMeters = currentAltitude,
                modifier = Modifier.padding(start = 8.dp)
            )
        }

        // --- 4. SHUTTER TRIGGER BAR ("CAPTURE") ---
        Button(
            onClick = { triggerSnapshot = true },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .width(85.dp)
                .height(200.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(Res.drawable.beluga_capture_sketch),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp))
                )
                // Letters stacked vertically (each upright, one per line) rather than the
                // whole word rotated sideways as a single string -- reads top to bottom.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        // Fully opaque, not translucent: at 0.75 alpha a dark region of the
                        // sketch image underneath (e.g. the eye) could show through strongly
                        // enough to blend with a letter and make it unreadable.
                        .background(Color.White)
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    val label = if (triggerSnapshot) "SAVING..." else "CAPTURE"
                    label.forEach { char ->
                        Text(
                            text = char.toString(),
                            color = Color.Black,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
