package com.cookinlet.belugas

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Side length of the square reticle below, in dp -- the single source of truth for "this is
// the capture area". Exposed so the platform camera capture code can crop the saved photo down
// to this same region without duplicating the magic number or risking it drifting out of sync
// with what's drawn on screen.
const val RETICLE_SIZE_DP = 180f

// Shared between the "BELUGAS" and "GO HERE" labels so the two roughly mirror each other's
// width (both are 7 characters) instead of the mismatched sizes they had before.
private const val LABEL_FONT_SIZE_SP = 20
private const val LABEL_LETTER_SPACING_SP = 3

@Composable
fun SketchedReticle(
    modifier: Modifier = Modifier,
    topLabelArtwork: (@Composable () -> Unit)? = null,
    bottomLabelArtwork: (@Composable () -> Unit)? = null,
    reticleArtwork: (@Composable () -> Unit)? = null,
    arrowsArtwork: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // --- 1. TOP PRINT: "BELUGAS", framed by arrows pointing down/inward at the reticle ---
        if (topLabelArtwork != null) {
            topLabelArtwork()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("↘", color = Color.Yellow, fontSize = LABEL_FONT_SIZE_SP.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "BELUGAS",
                    color = Color.Yellow,
                    fontSize = LABEL_FONT_SIZE_SP.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = LABEL_LETTER_SPACING_SP.sp,
                    textAlign = TextAlign.Center
                )
                Text("↙", color = Color.Yellow, fontSize = LABEL_FONT_SIZE_SP.sp, fontWeight = FontWeight.Bold)
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // --- 2. SQUARE RETICLE (180dp x 180dp) -- matches the actual photo crop area ---
        Box(
            modifier = Modifier.size(RETICLE_SIZE_DP.dp),
            contentAlignment = Alignment.Center
        ) {
            if (reticleArtwork != null) {
                reticleArtwork()
            } else {
                DefaultRectangularSketchedCanvas()
            }

            arrowsArtwork?.invoke()
        }

        Spacer(modifier = Modifier.height(4.dp))

        // --- 3. BOTTOM PRINT: "GO HERE", framed by arrows pointing up/inward at the reticle ---
        if (bottomLabelArtwork != null) {
            bottomLabelArtwork()
        } else {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("↗", color = Color.Yellow, fontSize = LABEL_FONT_SIZE_SP.sp, fontWeight = FontWeight.Bold)
                Text(
                    text = "GO HERE",
                    color = Color.Yellow,
                    fontSize = LABEL_FONT_SIZE_SP.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = LABEL_LETTER_SPACING_SP.sp,
                    textAlign = TextAlign.Center
                )
                Text("↖", color = Color.Yellow, fontSize = LABEL_FONT_SIZE_SP.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun DefaultRectangularSketchedCanvas() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val width = size.width
        val height = size.height
        val center = Offset(width / 2f, height / 2f)

        // Outer sketched rectangular frame
        drawRoundRect(
            color = Color.Yellow,
            topLeft = Offset(8f, 8f),
            size = Size(width - 16f, height - 16f),
            cornerRadius = CornerRadius(12.dp.toPx(), 12.dp.toPx()),
            style = Stroke(
                width = 3.dp.toPx(),
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 12f), 0f)
            )
        )

        // Inner corner framing ticks
        val tickLen = 24.dp.toPx()
        val margin = 20f

        // Top-Left corner
        drawLine(Color.Yellow, Offset(margin, margin), Offset(margin + tickLen, margin), 4.dp.toPx())
        drawLine(Color.Yellow, Offset(margin, margin), Offset(margin, margin + tickLen), 4.dp.toPx())

        // Top-Right corner
        drawLine(Color.Yellow, Offset(width - margin, margin), Offset(width - margin - tickLen, margin), 4.dp.toPx())
        drawLine(Color.Yellow, Offset(width - margin, margin), Offset(width - margin, margin + tickLen), 4.dp.toPx())

        // Bottom-Left corner
        drawLine(Color.Yellow, Offset(margin, height - margin), Offset(margin + tickLen, height - margin), 4.dp.toPx())
        drawLine(Color.Yellow, Offset(margin, height - margin), Offset(margin, height - margin - tickLen), 4.dp.toPx())

        // Bottom-Right corner
        drawLine(Color.Yellow, Offset(width - margin, height - margin), Offset(width - margin - tickLen, height - margin), 4.dp.toPx())
        drawLine(Color.Yellow, Offset(width - margin, height - margin), Offset(width - margin, height - margin - tickLen), 4.dp.toPx())

        // Center crosshair pip
        drawCircle(
            color = Color.Yellow,
            radius = 4.dp.toPx(),
            center = center
        )
    }
}
