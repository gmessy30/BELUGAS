package com.cookinlet.belugas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * One-shot compass read. Returns null if no compass sensor is available, or if a stable/
 * calibrated reading couldn't be obtained in time — callers should fall back to manual entry.
 */
expect class CompassService() {
    suspend fun getCurrentHeading(): HeadingEstimate?
}

@Composable
fun rememberCompassService(): CompassService = remember { CompassService() }
