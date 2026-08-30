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
// so the art shows through while keeping the existing palette and white-text legibility. A
// solid backing fill sits behind the image so its transparent regions read as part of the
// palette rather than showing through to whatever is beneath the Box. Used on every screen
// except Capture and Manual Report, which keep the live camera/map feed as their own
// full-bleed content.
@Composable
fun AppBackground(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier = modifier.fillMaxSize().background(Color(0xFF0D2E2E))) {
        Image(
            painter = painterResource(Res.drawable.beluga_background),
            contentDescription = null,
            contentScale = ContentScale.Crop,
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
