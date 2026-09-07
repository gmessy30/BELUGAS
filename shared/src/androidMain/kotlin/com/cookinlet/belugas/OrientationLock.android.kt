package com.cookinlet.belugas

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.platform.LocalContext

// LocalContext.current inside setContent {} is the Activity context, but may arrive wrapped
// (e.g. by a ContextThemeWrapper) -- this walks the wrapper chain to the real Activity.
private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/**
 * SENSOR_LANDSCAPE (not the fixed LANDSCAPE) so both landscape directions work via the sensor --
 * and deliberately overrides the phone's system-wide rotation-lock toggle too, since the point of
 * a screen-scoped lock is to force landscape regardless of what the user has set elsewhere.
 *
 * MainActivity already declares configChanges="orientation|screenSize|screenLayout" (needed
 * independently for CameraPreviewHost's own physical-rotation handling), so flipping
 * requestedOrientation here never recreates the Activity or loses App()'s Compose state.
 *
 * Ignored outright on large screens under Android 16 (targetSdk 36)'s large-screen orientation
 * restrictions -- there is no override for that, so on those devices these three screens fall
 * back to whatever orientation the window already is, and have to keep working there too.
 */
@Composable
actual fun LockLandscapeOrientation(enabled: Boolean) {
    val context = LocalContext.current
    DisposableEffect(enabled) {
        val activity = context.findActivity()
        val original = activity?.requestedOrientation
        if (enabled) {
            activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
        onDispose {
            if (enabled) {
                activity?.requestedOrientation = original ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            }
        }
    }
}
