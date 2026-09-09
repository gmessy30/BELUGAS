package com.cookinlet.belugas

import androidx.compose.runtime.Composable

// No system back button/gesture to intercept on iOS -- see AppBackHandler.kt's own comment.
@Composable
actual fun AppBackHandler(enabled: Boolean, onBack: () -> Unit) {
}
