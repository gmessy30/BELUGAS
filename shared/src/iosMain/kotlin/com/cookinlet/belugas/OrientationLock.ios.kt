package com.cookinlet.belugas

import androidx.compose.runtime.Composable

/**
 * No-op: iosApp/Info.plist's UISupportedInterfaceOrientations only lists the two landscape
 * orientations -- no portrait entries at all -- so this build of the iOS app already renders
 * landscape-only, everywhere, regardless of which screen is showing. That predates this feature
 * and looks like a leftover default rather than a deliberate choice (see App.kt's wantsLandscape
 * comment), but it's out of scope here -- there's no portrait state on iOS for this to lock away
 * from, so there's nothing for this to do.
 */
@Composable
actual fun LockLandscapeOrientation(enabled: Boolean) {
}
