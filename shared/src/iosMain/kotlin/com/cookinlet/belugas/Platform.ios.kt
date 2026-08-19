package com.cookinlet.belugas

import platform.UIKit.UIDevice
import org.maplibre.compose.map.MapOptions

class IOSPlatform: Platform {
    override val name: String = UIDevice.currentDevice.systemName() + " " + UIDevice.currentDevice.systemVersion
}

actual fun getPlatform(): Platform = IOSPlatform()

actual fun getMapOptions(): MapOptions = MapOptions()
