package com.cookinlet.belugas

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

// A cross-platform composable that renders the live native camera feed
@Composable
expect fun CameraPreviewHost(
    modifier: Modifier = Modifier,
    takePhotoSignal: Boolean,
    onPhotoCaptured: (String) -> Unit
)
