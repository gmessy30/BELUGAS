package com.cookinlet.belugas

import belugas.shared.generated.resources.Res
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.ExperimentalResourceApi

actual object MapManager {
    @OptIn(ExperimentalResourceApi::class)
    actual suspend fun prepareOfflineMap(storage: LocalFileStorage, mbtilesFileName: String): String = withContext(Dispatchers.Default) {
        val targetFilePath = storage.getFilePath(mbtilesFileName)

        if (!storage.fileExists(mbtilesFileName) || storage.getFileSize(mbtilesFileName) == 0L) {
            try {
                val bytes = Res.readBytes("files/$mbtilesFileName")
                storage.writeBytes(mbtilesFileName, bytes)
            } catch (e: Exception) {
                throw IllegalStateException("Failed to unpack '$mbtilesFileName' from resources.", e)
            }
        }

        return@withContext targetFilePath
    }
}
