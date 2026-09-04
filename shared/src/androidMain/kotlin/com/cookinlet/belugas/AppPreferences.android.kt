package com.cookinlet.belugas

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val PREFS_NAME = "belugas_prefs"
private const val KEY_LAUNCH_SCREEN = "launch_screen"
private const val KEY_FCM_TOKEN = "fcm_token"
private const val KEY_SUBSCRIBER_ID = "subscriber_id"

// Separate file, deliberately -- excluded from Auto Backup by androidApp's
// data_extraction_rules.xml/backup_rules.xml (both target this exact filename), while PREFS_NAME
// above keeps the default (backed-up) behavior. Splitting the file, not just the key, is what
// makes that exclusion possible -- Android's backup rules operate per shared_prefs file, not
// per key within one.
private const val NO_BACKUP_PREFS_NAME = "belugas_no_backup_prefs"
private const val KEY_HAS_ACKNOWLEDGED_FIRST_RUN_GATE = "has_acknowledged_first_run_gate"

actual class AppPreferences actual constructor() {
    private val prefs by lazy {
        androidContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }
    private val noBackupPrefs by lazy {
        androidContext.getSharedPreferences(NO_BACKUP_PREFS_NAME, Context.MODE_PRIVATE)
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

    actual suspend fun getSubscriberId(): String? = withContext(Dispatchers.IO) {
        prefs.getString(KEY_SUBSCRIBER_ID, null)
    }

    actual suspend fun setSubscriberId(id: String): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_SUBSCRIBER_ID, id).apply()
    }

    actual suspend fun getHasAcknowledgedFirstRunGate(): Boolean = withContext(Dispatchers.IO) {
        noBackupPrefs.getBoolean(KEY_HAS_ACKNOWLEDGED_FIRST_RUN_GATE, false)
    }

    actual suspend fun setHasAcknowledgedFirstRunGate(acknowledged: Boolean): Unit = withContext(Dispatchers.IO) {
        noBackupPrefs.edit().putBoolean(KEY_HAS_ACKNOWLEDGED_FIRST_RUN_GATE, acknowledged).apply()
    }
}
