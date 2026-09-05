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

    // commit(), not apply() -- this id is what a tier claim and every zone subscription bind
    // to server-side (see getOrCreateSubscriberId's own comment). apply() returns as soon as
    // the write is queued, not once it's durable -- a process death between generating a new
    // id and its write actually landing would silently start the device over as a different
    // (unrecognized) subscriber next launch, orphaning any claimed tier and subscriptions from
    // before. Fires once (whenever getOrCreateSubscriberId first generates an id) and once more
    // per explicit change (there is none today), off the main thread already -- commit()'s
    // synchronous cost here is negligible.
    actual suspend fun setSubscriberId(id: String): Unit = withContext(Dispatchers.IO) {
        prefs.edit().putString(KEY_SUBSCRIBER_ID, id).commit()
    }

    actual suspend fun getHasAcknowledgedFirstRunGate(): Boolean = withContext(Dispatchers.IO) {
        noBackupPrefs.getBoolean(KEY_HAS_ACKNOWLEDGED_FIRST_RUN_GATE, false)
    }

    // commit(), not apply() -- same reasoning as setSubscriberId above. onClick in
    // AcknowledgementGateScreen calls this then immediately navigates away in the same
    // coroutine; apply()'s write is only queued at that point, not durable, and nothing in
    // that path (no Activity pause/stop) triggers Android's own flush-pending-apply-writes
    // safety net. A process death in that window loses the write and the gate reappears next
    // launch even though the user genuinely acknowledged it. Fires once per install, already
    // off the main thread.
    actual suspend fun setHasAcknowledgedFirstRunGate(acknowledged: Boolean): Unit = withContext(Dispatchers.IO) {
        noBackupPrefs.edit().putBoolean(KEY_HAS_ACKNOWLEDGED_FIRST_RUN_GATE, acknowledged).commit()
    }
}
