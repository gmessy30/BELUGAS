package com.cookinlet.belugas

import androidx.compose.runtime.Composable
import coil3.ImageLoader
import coil3.compose.setSingletonImageLoaderFactory
import coil3.network.ktor3.KtorNetworkFetcherFactory

// Registers a single shared Coil ImageLoader for the whole app, wired up with the Ktor
// network fetcher so it can load sighting photos from their Supabase Storage public URLs.
// Coil doesn't auto-discover a network engine on every KMP target the way it can on
// Android/JVM alone, so this has to be set explicitly to work on iOS too.
@Composable
fun rememberSightingPhotoImageLoaderSetup() {
    setSingletonImageLoaderFactory { context ->
        ImageLoader.Builder(context)
            .components { add(KtorNetworkFetcherFactory()) }
            .build()
    }
}
