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

// Beluga artwork (transparent PNG) over the app's usual blue backdrop. The image is scaled to
// fit (never cropped) so the whole illustration stays on screen, letterboxed against an opaque
// backing that matches the blue the placeholder sketch used to show (its white page composited
// under the same translucent teal wash), so the artwork's transparent regions read as a
// continuation of the palette rather than a mismatched flat color. The artwork itself is drawn
// with no tint or overlay on top of it, so it renders in its own original colors. Used on every
// screen except Capture and Manual Report, which keep the live camera/map feed as their own
// full-bleed content.
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFF66B2B2), Color(0xFF669494))))
    ) {
        Image(
            painter = painterResource(Res.drawable.beluga_background),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
        )
        content()
    }
}
