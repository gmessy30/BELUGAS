package com.cookinlet.belugas

import android.os.Build
import org.maplibre.compose.map.MapOptions
import org.maplibre.compose.map.RenderOptions

class AndroidPlatform : Platform {
    override val name: String = "Android ${Build.VERSION.SDK_INT}"
}

actual fun getPlatform(): Platform = AndroidPlatform()

actual fun getMapOptions(): MapOptions = MapOptions(
    renderOptions = RenderOptions(
        renderMode = RenderOptions.RenderMode.TextureView
    )
)
