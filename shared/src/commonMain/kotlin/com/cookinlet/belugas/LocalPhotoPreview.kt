package com.cookinlet.belugas

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
expect fun LocalPhotoPreview(
    filePath: String,
    modifier: Modifier = Modifier
)
