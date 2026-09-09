package com.cookinlet.belugas

import androidx.compose.runtime.Composable

/**
 * Intercepts the system back button/gesture while [enabled] is true, invoking [onBack] instead
 * of the platform default. There's no navigation framework here -- App() just holds a single flat
 * currentScreen (see the Screen enum's own comment) -- so without this, system back falls through
 * to finishing the Activity and exiting the app straight from a sub-screen like Resources or
 * About, instead of returning to the main menu. No-op on iOS -- no hardware/system back gesture
 * to intercept there.
 */
@Composable
expect fun AppBackHandler(enabled: Boolean, onBack: () -> Unit)
