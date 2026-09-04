package com.cookinlet.belugas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import belugas.shared.generated.resources.Res
import belugas.shared.generated.resources.beluga_about_sketches

private const val LUNA_ARTWORK_INQUIRY_EMAIL = "REDACTED"

// Hidden gesture into the observer-tier claim screen (see TierClaimScreen.kt) -- 7 taps on the
// nose of the whale labeled "B" in Luna's own reference sketch (see AboutHiddenGesture.kt for
// the proportional tap-zone math and why it has to be proportional, not fixed pixels) within a
// rolling window, mirroring Android's own long-precedented "tap the build number 7 times"
// developer-options convention: boring, easy to implement correctly, and not something a user
// brushes into by accident. Registers ONLY while the text overlay is hidden (showText == false)
// -- deliberate extra accident-proofing on top of "a specific whale's nose," not an oversight;
// with text shown, the same taps do nothing. No visible hint anywhere that this exists -- the tap
// count resets (rather than accumulating indefinitely) after any gap longer than
// HIDDEN_GESTURE_TAP_TIMEOUT_MS between taps.
private const val HIDDEN_GESTURE_TAP_COUNT = 7
private const val HIDDEN_GESTURE_TAP_TIMEOUT_MS = 1500L

// Multiplies the sketch scan's white paper into the app's teal backdrop color (pencil lines stay
// dark since black is unaffected by a multiply blend), so the page reads as part of the
// background rather than a stark white rectangle floating on it.
private val SketchBackgroundTint = ColorFilter.tint(Color(0xFF66A3A3), BlendMode.Multiply)

@Composable
fun AboutScreen(onBack: () -> Unit, onNavigateToTierClaim: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var showText by remember { mutableStateOf(true) }

    // Hidden gesture state -- see HIDDEN_GESTURE_TAP_COUNT's own comment above.
    var hiddenGestureTapCount by remember { mutableStateOf(0) }
    var hiddenGestureLastTapAt by remember { mutableStateOf(0L) }
    var artworkContainerSizePx by remember { mutableStateOf(IntSize.Zero) }
    fun onWhaleNoseTap() {
        val now = currentTimeMillis()
        hiddenGestureTapCount =
            if (now - hiddenGestureLastTapAt <= HIDDEN_GESTURE_TAP_TIMEOUT_MS) hiddenGestureTapCount + 1 else 1
        hiddenGestureLastTapAt = now
        if (hiddenGestureTapCount >= HIDDEN_GESTURE_TAP_COUNT) {
            hiddenGestureTapCount = 0
            onNavigateToTierClaim()
        }
    }

    AppBackground(
        backgroundImage = Res.drawable.beluga_about_sketches,
        backgroundColorFilter = SketchBackgroundTint
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        // Whale-B-nose tap detector -- placed as the first (bottommost) child here so the header
        // row's own buttons below (drawn on top) still get first claim on their own taps; this
        // only ever sees taps that fall through empty artwork space. Sized to match the full
        // container, same as AppBackground's own Image(contentScale = Fit) -- see
        // AboutHiddenGesture.kt for why the tap has to be un-letterboxed against that same size
        // before it can be checked against the whale's proportional bounds.
        Box(
            modifier = Modifier
                .fillMaxSize()
                .onSizeChanged { artworkContainerSizePx = it }
                .pointerInput(Unit) {
                    detectTapGestures { offset ->
                        if (showText) return@detectTapGestures
                        val size = artworkContainerSizePx
                        if (size.width <= 0 || size.height <= 0) return@detectTapGestures
                        val point = normalizedArtworkPoint(
                            tapX = offset.x,
                            tapY = offset.y,
                            containerWidth = size.width.toFloat(),
                            containerHeight = size.height.toFloat()
                        ) ?: return@detectTapGestures
                        if (isWithinWhaleBNose(point)) onWhaleNoseTap()
                    }
                }
        )
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "ABOUT",
                    color = Color.White,
                    fontSize = 22.sp,
                    fontWeight = FontWeight.Black
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { showText = !showText }) {
                        Text(
                            if (showText) "HIDE TEXT" else "SHOW TEXT",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    TextButton(onClick = onBack) {
                        Text("← BACK", color = Color.Yellow, fontWeight = FontWeight.Bold)
                    }
                }
            }

            if (showText) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    AboutSection(title = "DEVELOPMENT") {
                        Text(
                            "BELUGAS is developed by Ryan Messimer.",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp
                        )
                    }

                    AboutSection(title = "ARTWORK") {
                        Text(
                            "The beluga artwork used throughout the app is by Luna.",
                            color = Color.White.copy(alpha = 0.85f),
                            fontSize = 13.sp
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "For inquiries about Luna's artwork: $LUNA_ARTWORK_INQUIRY_EMAIL",
                            color = Color(0xFF00E5FF),
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.clickable { uriHandler.openUri("mailto:$LUNA_ARTWORK_INQUIRY_EMAIL") }
                        )
                    }

                    Spacer(Modifier.height(24.dp))
                }
            }
        }
    }
    }
}

@Composable
private fun AboutSection(title: String, content: @Composable ColumnScope.() -> Unit) {
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
