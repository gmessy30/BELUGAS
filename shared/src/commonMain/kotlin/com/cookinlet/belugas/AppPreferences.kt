package com.cookinlet.belugas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Which screen to land on after the splash screen. Names are user-facing concepts
 * ("Camera"/"Map"/"Menu"), distinct from the internal Screen enum's CAPTURE/etc. states.
 */
enum class LaunchScreen(val label: String) {
    CAMERA("Camera"),
    MAP("Map"),
    MENU("Menu")
}

/**
 * Small local device-preference store (SharedPreferences on Android, NSUserDefaults on iOS).
 * Holds the launch-screen preference and the device's current FCM token; not meant to grow
 * into a general key/value store without deciding that deliberately.
 */
expect class AppPreferences() {
    suspend fun getLaunchScreen(): LaunchScreen
    suspend fun setLaunchScreen(screen: LaunchScreen)

    // Set from BelugasMessagingService.onNewToken (Android only for now -- iOS APNs/FCM setup
    // is deferred). Stored here rather than only logged so it survives process death and is
    // reachable from commonMain once it's wired to subscriptions.subscriber_id in Supabase --
    // that wiring is a deliberate follow-up, not built yet.
    suspend fun getFcmToken(): String?
    suspend fun setFcmToken(token: String)
}

@Composable
fun rememberAppPreferences(): AppPreferences = remember { AppPreferences() }
