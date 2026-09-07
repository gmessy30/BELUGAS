package com.cookinlet.belugas

import androidx.compose.runtime.Composable

/**
 * Forces the device into landscape while [enabled] is true, releasing back to whatever
 * orientation setting was active before it turned on when it becomes false again (or this leaves
 * composition). See the platform actuals for why this only does something on Android today.
 */
@Composable
expect fun LockLandscapeOrientation(enabled: Boolean)
