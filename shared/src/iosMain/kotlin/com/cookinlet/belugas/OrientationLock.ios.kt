package com.cookinlet.belugas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import platform.Foundation.*
import platform.UIKit.*

/**
 * Forces landscape while [enabled] is true, via the UIDevice "orientation" key-value-coding
 * trick (setValue(_:forKey:"orientation")) -- the standard way to force a rotation from shared/
 * SwiftUI code when there's no custom UIViewController subclass to override
 * supportedInterfaceOrientations on. This app hosts everything through a single
 * ComposeUIViewController (MainViewController.kt) with no such subclass.
 *
 * Requires Info.plist's UISupportedInterfaceOrientations to actually list the target
 * orientation -- iOS silently ignores the forced value otherwise. Portrait was added alongside
 * both landscapes for this reason (see Info.plist) -- previously the whole app was
 * landscape-only regardless of which screen was showing, which is what made this function a
 * no-op before.
 *
 * Not restored on disable/dispose, unlike the Android actual (OrientationLock.android.kt),
 * which explicitly restores the prior orientation because SCREEN_ORIENTATION_SENSOR_LANDSCAPE
 * otherwise stays pinned after the app moves off the locked screen. iOS has no equivalent
 * persistent lock to release -- once this stops forcing landscape, the OS's own auto-rotation
 * takes back over on the device's actual physical orientation, so there's nothing to restore to.
 *
 * COMPILE-TIME VERIFIED ONLY (:shared:compileKotlinIosArm64) -- no Mac/Xcode available in this
 * environment to run this on a device or simulator and confirm the rotation actually happens.
 */
@Composable
actual fun LockLandscapeOrientation(enabled: Boolean) {
    DisposableEffect(enabled) {
        if (enabled) {
            val orientation = NSNumber(unsignedLong = UIInterfaceOrientationLandscapeRight.toULong())
            UIDevice.currentDevice.setValue(orientation, forKey = "orientation")
            UIViewController.attemptRotationToDeviceOrientation()
        }
        onDispose {}
    }
}
