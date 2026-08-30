package com.cookinlet.belugas

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import belugas.shared.generated.resources.Res
import belugas.shared.generated.resources.beluga_background
import org.jetbrains.compose.resources.painterResource

// Beluga artwork (transparent PNG) behind the app's usual teal gradient, tinted translucent
// so the art shows through while keeping the existing palette and white-text legibility. The
// image is scaled to fit (never cropped) so the whole illustration stays on screen, letterboxed
// against an opaque backing in the same teal tones as the gradient overlay, so the artwork's
// transparent regions read as a continuation of the palette rather than a mismatched flat color.
// Used on every screen except Capture and Manual Report, which keep the live camera/map feed as
// their own full-bleed content.
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFF007F7F), Color(0xFF004D4D))))
    ) {
        Image(
            painter = painterResource(Res.drawable.beluga_background),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(colors = listOf(Color(0x99007F7F), Color(0x99004D4D)))
                )
        )
        content()
    }
}
