package com.cookinlet.belugas

import org.maplibre.compose.map.MapOptions

interface Platform {
    val name: String
}

expect fun getPlatform(): Platform

expect fun getMapOptions(): MapOptions
