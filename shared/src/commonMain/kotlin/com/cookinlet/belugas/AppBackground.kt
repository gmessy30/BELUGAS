package com.cookinlet.belugas

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.LocalTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import belugas.shared.generated.resources.Res
import belugas.shared.generated.resources.beluga_background
import org.jetbrains.compose.resources.DrawableResource
import org.jetbrains.compose.resources.painterResource

// Beluga artwork over the app's usual blue backdrop -- beluga_background (a transparent PNG) by
// default, or whatever `backgroundImage` a screen supplies (e.g. the About page's sketch page).
// The image is scaled to fit (never cropped) so the whole illustration stays on screen,
// letterboxed against an opaque backing that matches the blue the placeholder sketch used to
// show (its white page composited under the same translucent teal wash), so transparent regions
// read as a continuation of the palette rather than a mismatched flat color. By default the
// artwork is drawn with no tint or overlay on top of it, so it renders in its own original
// colors -- `backgroundColorFilter` is an opt-in escape hatch for a screen like About, whose
// scanned sketch page has its own white paper background rather than transparency, so it needs
// a tint to blend into the letterboxed backdrop instead of showing a stark white rectangle.
// `backgroundImageAlpha` lets a screen fade the image in/out over the opaque backdrop -- the
// splash screen uses it to hold on the plain backdrop and icon for a beat before the artwork
// appears. Used on every screen except Capture and Manual Report, which keep the live
// camera/map feed as their own full-bleed content.
//
// The artwork's light body tones leave standard white text with too little contrast, so a
// subtle drop shadow is applied to every Text in `content` via LocalTextStyle -- Text() merges
// its explicit color/fontSize params onto this base style, so the shadow carries through
// without every screen needing to set it individually.
private val TextLegibilityShadow = Shadow(color = Color.Black.copy(alpha = 0.55f), offset = Offset(0f, 1f), blurRadius = 3f)

@Composable
fun AppBackground(
    modifier: Modifier = Modifier,
    backgroundImage: DrawableResource = Res.drawable.beluga_background,
    backgroundColorFilter: ColorFilter? = null,
    backgroundImageAlpha: Float = 1f,
    content: @Composable BoxScope.() -> Unit
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(colors = listOf(Color(0xFF66B2B2), Color(0xFF669494))))
    ) {
        Image(
            painter = painterResource(backgroundImage),
            contentDescription = null,
            contentScale = ContentScale.Fit,
            colorFilter = backgroundColorFilter,
            alpha = backgroundImageAlpha,
            modifier = Modifier.fillMaxSize()
        )
        CompositionLocalProvider(
            LocalTextStyle provides LocalTextStyle.current.copy(shadow = TextLegibilityShadow)
        ) {
            content()
        }
    }
}
