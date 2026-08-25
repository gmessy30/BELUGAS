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

// Size of the rectangular reticle below, in dp. Exposed so the platform camera capture code
// can crop the saved photo down to this same region without duplicating the magic numbers.
const val RETICLE_WIDTH_DP = 360f
const val RETICLE_HEIGHT_DP = 180f

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
        // --- 1. TOP PRINT: "BELUGAS" ---
        if (topLabelArtwork != null) {
            topLabelArtwork()
        } else {
            Text(
                text = "BELUGAS",
                color = Color.Yellow,
                fontSize = 22.sp,
                fontWeight = FontWeight.Black,
                letterSpacing = 4.sp,
                textAlign = TextAlign.Center
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // --- 2. RECTANGULAR LANDSCAPE RETICLE (360dp x 180dp) ---
        Box(
            modifier = Modifier.size(width = RETICLE_WIDTH_DP.dp, height = RETICLE_HEIGHT_DP.dp),
            contentAlignment = Alignment.Center
        ) {
            if (reticleArtwork != null) {
                reticleArtwork()
            } else {
                DefaultRectangularSketchedCanvas()
            }

            if (arrowsArtwork != null) {
                arrowsArtwork()
            } else {
                DefaultDirectionalArrowsOverlay()
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        // --- 3. BOTTOM PRINT: "GO HERE" ---
        if (bottomLabelArtwork != null) {
            bottomLabelArtwork()
        } else {
            Text(
                text = "▼ GO HERE ▼",
                color = Color.Yellow,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                textAlign = TextAlign.Center
            )
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

@Composable
private fun DefaultDirectionalArrowsOverlay() {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = "▲ AWAY",
            color = Color.Yellow,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.TopCenter).padding(top = 12.dp)
        )
        Text(
            text = "◄ LEFT",
            color = Color.Yellow,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterStart).padding(start = 12.dp)
        )
        Text(
            text = "RIGHT ►",
            color = Color.Yellow,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.align(Alignment.CenterEnd).padding(end = 12.dp)
        )
    }
}
