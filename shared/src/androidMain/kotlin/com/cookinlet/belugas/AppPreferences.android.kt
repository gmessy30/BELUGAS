package com.cookinlet.belugas

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "belugas_prefs"
private const val KEY_LAUNCH_SCREEN = "launch_screen"
private const val KEY_FCM_TOKEN = "fcm_token"

actual class AppPreferences actual constructor() {
    private val prefs by lazy {
        androidContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    actual suspend fun getLaunchScreen(): LaunchScreen = withContext(Dispatchers.IO) {
        val stored = prefs.getString(KEY_LAUNCH_SCREEN, null)
        LaunchScreen.entries.firstOrNull { it.name == stored } ?: LaunchScreen.CAMERA
    }

    actual suspend fun setLaunchScreen(screen: LaunchScreen): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_LAUNCH_SCREEN, screen.name).apply()
    }

    actual suspend fun getFcmToken(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_FCM_TOKEN, null)
    }

    actual suspend fun setFcmToken(token: String): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_FCM_TOKEN, token).apply()
    }
}
