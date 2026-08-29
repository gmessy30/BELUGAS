package com.cookinlet.belugas

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    // Declaring android.permission.POST_NOTIFICATIONS in the manifest isn't enough on its
    // own on Android 13+ (API 33) -- it's a runtime "dangerous" permission, so an FCM
    // notification message would otherwise silently never display without this being granted.
    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* no-op either way */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        androidContext = applicationContext
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        // BelugasMessagingService.onNewToken only fires when the token is first generated or
        // actually changes -- a device that already has a token from before device_tokens
        // existed would never get (re-)registered otherwise. Re-registering the current token
        // on every launch is a cheap idempotent upsert, so this just keeps every install in
        // sync regardless of when it first got its token.
        //
        // .token is deprecated as of firebase-messaging 25.1.0 (see the same note on
        // BelugasMessagingService.onNewToken -- FCM is moving toward Firebase Installation ID
        // registration, but that replacement isn't clearly documented yet).
        @Suppress("DEPRECATION")
        val tokenTask = FirebaseMessaging.getInstance().token
        tokenTask.addOnCompleteListener { task ->
            if (task.isSuccessful) {
                val token = task.result
                CoroutineScope(Dispatchers.IO).launch {
                    val appPreferences = AppPreferences()
                    appPreferences.setFcmToken(token)
                    SupabaseApi.registerDeviceToken(token, appPreferences.getOrCreateSubscriberId())
                }
            }
        }

        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}