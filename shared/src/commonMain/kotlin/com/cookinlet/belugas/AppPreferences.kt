package com.cookinlet.belugas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlin.random.Random

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

    // Anonymous per-device id for the notification-zone subscriptions system (see
    // supabase/migrations/20260819000000_add_notification_zones_and_subscriptions.sql's note
    // on the auth migration path this is meant to fit into once real accounts exist). Raw
    // storage only -- getOrCreateSubscriberId() below is what generates one.
    suspend fun getSubscriberId(): String?
    suspend fun setSubscriberId(id: String)

    // The first-run acknowledgement gate's "already shown" flag (respect private property, log
    // where the whales were seen rather than where you stood -- see App.kt's routing and
    // AcknowledgementGateScreen.kt). Deliberately NOT stored alongside launchScreen/fcmToken/
    // subscriberId above: this one is meant to reappear after a reinstall, so on Android it's
    // backed by a separate SharedPreferences file excluded from Auto Backup (see
    // AppPreferences.android.kt + androidApp's data_extraction_rules.xml/backup_rules.xml),
    // while subscriberId is meant to keep surviving Auto Backup so zone subscriptions aren't
    // silently lost on a reinstall. Android-only feature, intentionally (see
    // AppPreferences.ios.kt's own comment) -- iOS's actual always reports "already acknowledged"
    // rather than attempting an equivalent NSUserDefaults-backup-exclusion trick.
    suspend fun getHasAcknowledgedFirstRunGate(): Boolean
    suspend fun setHasAcknowledgedFirstRunGate(acknowledged: Boolean)
}

@Composable
fun rememberAppPreferences(): AppPreferences = remember { AppPreferences() }

/**
 * Returns this device's subscriber id, generating and persisting a random UUIDv4 the first
 * time it's called so every later call (even across process death) returns the same value.
 */
suspend fun AppPreferences.getOrCreateSubscriberId(): String {
    getSubscriberId()?.let { return it }
    val newId = randomUuidV4()
    setSubscriberId(newId)
    return newId
}

// Hand-rolled rather than kotlin.uuid.Uuid to avoid pulling in that still-experimental API
// for a single call site.
private fun randomUuidV4(): String {
    val bytes = ByteArray(16)
    Random.nextBytes(bytes)
    bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x40).toByte() // version 4
    bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // variant 1 (RFC 4122)
    val hex = bytes.toHex()
    return buildString {
        append(hex, 0, 8); append('-')
        append(hex, 8, 12); append('-')
        append(hex, 12, 16); append('-')
        append(hex, 16, 20); append('-')
        append(hex, 20, 32)
    }
}

private fun ByteArray.toHex(): String {
    val hexChars = "0123456789abcdef"
    val sb = StringBuilder(size * 2)
    for (b in this) {
        val i = b.toInt() and 0xFF
        sb.append(hexChars[i shr 4])
        sb.append(hexChars[i and 0x0F])
    }
    return sb.toString()
}
