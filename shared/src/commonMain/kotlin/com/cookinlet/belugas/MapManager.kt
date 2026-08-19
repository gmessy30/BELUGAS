package com.cookinlet.belugas

expect object MapManager {
    suspend fun prepareOfflineMap(storage: LocalFileStorage, mbtilesFileName: String): String
}
