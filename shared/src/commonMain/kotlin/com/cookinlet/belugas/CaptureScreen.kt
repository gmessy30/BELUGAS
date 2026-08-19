package com.cookinlet.belugas

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9800)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .width(85.dp)
                .height(200.dp)
        ) {
            Text(
                text = if (triggerSnapshot) "SAVING..." else "CAPTURE",
                color = Color.Black,
                fontSize = 16.sp,
                fontWeight = FontWeight.Black,
                textAlign = TextAlign.Center
            )
        }
    }
}
