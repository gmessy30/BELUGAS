package com.cookinlet.belugas

import android.util.Log
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

private const val TAG = "BelugasFCM"

/**
 * Registers the device for FCM and captures its token. Dispatch (subscribing this token to
 * zones via the subscriptions table's subscriber_id) is a deliberate follow-up, not built
 * here -- this is just getting the device registered and the token persisted somewhere the
 * shared layer can read it from later.
 */
class BelugasMessagingService : FirebaseMessagingService() {

    override fun onCreate() {
        super.onCreate()
        // FCM can start this service in a fresh process (e.g. after a token refresh while the
        // app isn't running) without MainActivity ever having run first, so androidContext
        // (normally set in MainActivity.onCreate) needs its own initialization here too --
        // AppPreferences below depends on it being set.
        androidContext = applicationContext
    }

    // onNewToken was deprecated in firebase-messaging 25.1.0 (June 2026, the version this
    // project is pinned to) as FCM starts moving toward Firebase Installation ID (FID) based
    // registration -- but that replacement isn't clearly documented yet and onNewToken still
    // works as the standard way to capture a refreshed token. Revisit once Firebase's FID
    // migration path is actually spelled out rather than adopting something unstable now.
    @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM token: $token")
        CoroutineScope(Dispatchers.IO).launch {
            AppPreferences().setFcmToken(token)
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        super.onMessageReceived(message)
        Log.d(TAG, "Message received. data=${message.data} notification=${message.notification?.body}")
    }
}
