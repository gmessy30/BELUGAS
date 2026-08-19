package com.cookinlet.belugas

import java.io.File
import java.io.FileOutputStream

actual object MapManager {
    private const val TAG = "MapManager"

    actual suspend fun prepareOfflineMap(storage: LocalFileStorage, mbtilesFileName: String): String {
        val cacheDir = File(storage.getCacheDir())
        val targetFile = File(cacheDir, mbtilesFileName)
        println("$TAG: Preparing map '$mbtilesFileName' at ${targetFile.absolutePath}")

        if (!targetFile.exists() || targetFile.length() == 0L) {
            println("$TAG: Streaming from assets...")
            
            // Ensure cache directory exists
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }

            try {
                // Stream chunks from assets into internal storage
                androidContext.assets.open(mbtilesFileName).use { inputStream ->
                    FileOutputStream(targetFile).use { outputStream ->
                        inputStream.copyTo(outputStream, bufferSize = 16 * 1024)
                    }
                }
                println("$TAG: SUCCESS - Extracted ${targetFile.length()} bytes.")
            } catch (e: Exception) {
                // Log all assets found in the APK to aid debugging
                val assetList = androidContext.assets.list("")?.joinToString(", ") ?: "None"
                println("$TAG: ERROR - ${e.message}. Assets found: [$assetList]")
                throw IllegalStateException(
                    "Failed to stream '$mbtilesFileName' from APK assets. Assets found in APK: [$assetList]",
                    e
                )
            }
        } else {
            println("$TAG: Using existing cache (${targetFile.length()} bytes).")
        }

        return targetFile.absolutePath
    }
}
