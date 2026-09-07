package com.cookinlet.belugas

import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import belugas.shared.generated.resources.Calfbreaching
import belugas.shared.generated.resources.Greybreaching
import belugas.shared.generated.resources.Res
import belugas.shared.generated.resources.Unknownbreaching
import belugas.shared.generated.resources.Whitebreaching
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// Source art is a 280x120 crop with the whale centred in the transparent canvas (see the
// breaching-whale button work) -- kept in sync here so the on-screen button always uses the
// same aspect ratio as the asset, regardless of how wide the row ends up on a given device.
private val WHALE_BUTTON_ASPECT_RATIO = 280f / 120f
private val COUNT_TEXT_SIZE = 20.sp
private val GLYPH_TEXT_SIZE = 22.sp

/**
 * White text with a black outline -- Compose has no text-stroke property, so this fakes one with
 * two stacked Text composables (a black copy with a stroke drawstyle underneath, solid white on
 * top). Shared by the count and the "+"/"-" glyphs: ManualLoggingScreen renders this row directly
 * over a pannable map, so a plain white glyph can land on a pale channel and disappear -- the
 * outline is what keeps all three legible against whatever's behind them, not just the count.
 */
@Composable
private fun OutlinedText(text: String, fontSize: androidx.compose.ui.unit.TextUnit) {
    val strokeWidthPx = with(LocalDensity.current) { fontSize.toPx() / 6f }
    Text(
        text = text,
        color = Color.Black,
        fontSize = fontSize,
        fontWeight = FontWeight.Black,
        style = TextStyle(drawStyle = Stroke(width = strokeWidthPx, join = StrokeJoin.Round))
    )
    Text(
        text = text,
        color = Color.White,
        fontSize = fontSize,
        fontWeight = FontWeight.Black
    )
}

private enum class WhaleTier(val drawable: DrawableResource, val contentDescription: String) {
    WHITE(Res.drawable.Whitebreaching, "White whale count"),
    GREY(Res.drawable.Greybreaching, "Grey whale count"),
    UNKNOWN(Res.drawable.Unknownbreaching, "Silvertine (unknown color) whale count"),
    CALF(Res.drawable.Calfbreaching, "Calf count")
}

/**
 * Four whale-count buttons in a row, order Unknown (Silvertines) / Grey / Calf / White, left to
 * right -- Whites stay nearest the right thumb since white whales are the most-sighted tier for a
 * right-handed observer. Shared between LoggingScreen (Camera flow) and ManualLoggingScreen so
 * both screens count the same way. Each whale IS the button (no container box, no rounded rect):
 * a bare "+" above it, the running count over it, a bare "-" below it. Equal-width via weight(1f)
 * so the row always spans the screen edge to edge regardless of device width -- see the sizing
 * note captured alongside this feature's build for the resulting per-button dimensions across
 * common phone widths.
 *
 * Each WhaleCountButton call below binds its count/handlers to its WhaleTier by name, not by
 * position in this list -- reordering these calls only changes visual left-to-right placement,
 * never which count a given whale increments/decrements.
 */
@Composable
fun WhaleCountRow(
    whiteCount: Int,
    onWhiteIncrement: () -> Unit,
    onWhiteDecrement: () -> Unit,
    greyCount: Int,
    onGreyIncrement: () -> Unit,
    onGreyDecrement: () -> Unit,
    unknownCount: Int,
    onUnknownIncrement: () -> Unit,
    onUnknownDecrement: () -> Unit,
    calfCount: Int,
    onCalfIncrement: () -> Unit,
    onCalfDecrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
    ) {
        WhaleCountButton(WhaleTier.UNKNOWN, unknownCount, onUnknownIncrement, onUnknownDecrement, Modifier.weight(1f))
        WhaleCountButton(WhaleTier.GREY, greyCount, onGreyIncrement, onGreyDecrement, Modifier.weight(1f))
        WhaleCountButton(WhaleTier.CALF, calfCount, onCalfIncrement, onCalfDecrement, Modifier.weight(1f))
        WhaleCountButton(WhaleTier.WHITE, whiteCount, onWhiteIncrement, onWhiteDecrement, Modifier.weight(1f))
    }
}

@Composable
private fun WhaleCountButton(
    tier: WhaleTier,
    count: Int,
    onIncrement: () -> Unit,
    onDecrement: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = modifier
    ) {
        // Bare "+", no background -- the 48dp-min touch target is independent of how small the
        // glyph or the artwork below it renders, so gloved hands on a boat still get a real hit area.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = "Increase") { onIncrement() },
            contentAlignment = Alignment.Center
        ) {
            OutlinedText("+", GLYPH_TEXT_SIZE)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(WHALE_BUTTON_ASPECT_RATIO),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(tier.drawable),
                contentDescription = tier.contentDescription,
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxSize()
            )

            // Centered over the same Box as the image, so it lands squarely on the whale's
            // (centred) body.
            OutlinedText("$count", COUNT_TEXT_SIZE)
        }

        // Bare "-", same treatment as the "+" above.
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .clickable(onClickLabel = "Decrease") { onDecrement() },
            contentAlignment = Alignment.Center
        ) {
            OutlinedText("−", GLYPH_TEXT_SIZE)
        }
    }
}
