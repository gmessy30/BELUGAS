package com.cookinlet.belugas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSUserDefaults

private const val KEY_LAUNCH_SCREEN = "launch_screen"
private const val KEY_FCM_TOKEN = "fcm_token"

actual class AppPreferences actual constructor() {
    private val defaults = NSUserDefaults.standardUserDefaults

    actual suspend fun getLaunchScreen(): LaunchScreen = withContext(Dispatchers.Default) {
        val stored = defaults.stringForKey(KEY_LAUNCH_SCREEN)
        LaunchScreen.entries.firstOrNull { it.name == stored } ?: LaunchScreen.CAMERA
    }

    actual suspend fun setLaunchScreen(screen: LaunchScreen): Unit = withContext(Dispatchers.Default) {
        defaults.setObject(screen.name, forKey = KEY_LAUNCH_SCREEN)
    }

    // Nothing calls this yet -- iOS push (APNs/FCM) setup is deferred until there's an Apple
    // Developer account. Implemented now only so the expect/actual contract stays satisfied.
    actual suspend fun getFcmToken(): String? = withContext(Dispatchers.Default) {
        defaults.stringForKey(KEY_FCM_TOKEN)
    }

    actual suspend fun setFcmToken(token: String): Unit = withContext(Dispatchers.Default) {
        defaults.setObject(token, forKey = KEY_FCM_TOKEN)
    }
}
