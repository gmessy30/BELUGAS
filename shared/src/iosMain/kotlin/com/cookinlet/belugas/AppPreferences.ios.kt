package com.cookinlet.belugas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.NSUserDefaults

private const val KEY_LAUNCH_SCREEN = "launch_screen"
private const val KEY_FCM_TOKEN = "fcm_token"
private const val KEY_SUBSCRIBER_ID = "subscriber_id"

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

    actual suspend fun getSubscriberId(): String? = withContext(Dispatchers.Default) {
        defaults.stringForKey(KEY_SUBSCRIBER_ID)
    }

    actual suspend fun setSubscriberId(id: String): Unit = withContext(Dispatchers.Default) {
        defaults.setObject(id, forKey = KEY_SUBSCRIBER_ID)
    }

    // The first-run acknowledgement gate is Android-only, intentionally -- same "no Apple
    // Developer account yet" reason getFcmToken/setFcmToken above is a no-op in practice. Rather
    // than attempt an NSUserDefaults equivalent of Android's Auto-Backup-exclusion trick (there
    // isn't a clean one: NSUserDefaults has no per-key or per-suite backup exclusion, so
    // reproducing "must reappear after a reinstall" here would mean moving this one flag into a
    // file with NSURLIsExcludedFromBackupKey set, a real behavior difference from every other
    // preference in this class for a screen iOS never shows) -- this just always reports
    // "already acknowledged" so the gate can never trigger here, and set is a no-op. Revisit for
    // real if iOS ever gets its own build of this gate.
    actual suspend fun getHasAcknowledgedFirstRunGate(): Boolean = true

    actual suspend fun setHasAcknowledgedFirstRunGate(acknowledged: Boolean): Unit = Unit
}
