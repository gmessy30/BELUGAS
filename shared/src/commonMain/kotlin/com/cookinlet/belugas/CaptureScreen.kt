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
import belugas.shared.generated.resources.beluga_capture_button
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

        // --- 3b. ONE-PHOTO-PER-SIGHTING REMINDER ---
        // Bottom-center, clear of everything else on this screen: the reticle's own "GO HERE"
        // label already owns vertical-center, the shutter sits at CenterEnd, and the top-left is
        // the MENU/sync-badge/elevation-tip column -- that leaves the bottom strip free except
        // for the system nav bar, cleared the same way the bottom panels on ManualLoggingScreen/
        // LoggingScreen already do. Small and muted so it reads as a quiet reminder, not a
        // warning competing with the reticle for attention.
        Text(
            text = "One photo per sighting, please",
            color = Color.White.copy(alpha = 0.6f),
            fontSize = 12.sp,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 12.dp)
        )

        // --- 4. SHUTTER TRIGGER BAR ("CAPTURE") ---
        Button(
            onClick = { triggerSnapshot = true },
            colors = ButtonDefaults.buttonColors(containerColor = Color.White),
            shape = RoundedCornerShape(24.dp),
            contentPadding = PaddingValues(0.dp),
            modifier = Modifier
                .align(Alignment.CenterEnd)
                .padding(end = 16.dp)
                .width(99.dp)
                .height(200.dp)
        ) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Image(
                    painter = painterResource(Res.drawable.beluga_capture_button),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(24.dp))
                )
                // Letters stacked vertically (each upright, one per line) rather than the
                // whole word rotated sideways as a single string -- reads top to bottom.
                // Positioned in the artwork's own negative-space corridor (left edge, below the
                // head/dorsal line) rather than dead center over the whale's body -- a plain dp
                // offset is safe here specifically because this button is a fixed 99dp x 200dp
                // box on every device (not screen-relative like AppBackground's full-bleed
                // artwork), so there's no scaling for the offset to drift against.
                //
                // Aligned TopStart with an explicit top padding, not CenterStart -- centering
                // vertically across the FULL button height put the first couple characters back
                // over the head/dorsal line, since that negative-space corridor only occupies
                // the lower portion of the button, not its full height. Font size/line height
                // shrunk too so the whole word compresses into that corridor's actual height
                // instead of spilling above it regardless of where the block starts.
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(start = 6.dp, top = 70.dp)
                ) {
                    val label = if (triggerSnapshot) "SAVING..." else "CAPTURE"
                    label.forEach { char ->
                        Text(
                            text = char.toString(),
                            color = Color.Black,
                            fontSize = 12.sp,
                            lineHeight = 12.sp,
                            fontWeight = FontWeight.Black,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }
}
