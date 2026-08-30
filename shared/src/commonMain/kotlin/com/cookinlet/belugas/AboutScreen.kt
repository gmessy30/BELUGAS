package com.cookinlet.belugas

import androidx.compose.foundation.clickable
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
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import belugas.shared.generated.resources.Res
import belugas.shared.generated.resources.beluga_about_sketches

private const val LUNA_ARTWORK_INQUIRY_EMAIL = "REDACTED"

// Multiplies the sketch scan's white paper into the app's teal backdrop color (pencil lines stay
// dark since black is unaffected by a multiply blend), so the page reads as part of the
// background rather than a stark white rectangle floating on it.
private val SketchBackgroundTint = ColorFilter.tint(Color(0xFF66A3A3), BlendMode.Multiply)

@Composable
fun AboutScreen(onBack: () -> Unit) {
    val uriHandler = LocalUriHandler.current
    var showText by remember { mutableStateOf(true) }

    AppBackground(
        backgroundImage = Res.drawable.beluga_about_sketches,
        backgroundColorFilter = SketchBackgroundTint
    ) {
    Box(modifier = Modifier.fillMaxSize()) {
        Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("ABOUT", color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
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
