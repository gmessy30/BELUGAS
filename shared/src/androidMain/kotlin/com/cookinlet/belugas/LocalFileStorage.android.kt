package com.cookinlet.belugas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

actual class LocalFileStorage actual constructor() {
    actual suspend fun writeText(fileName: String, content: String) = withContext(Dispatchers.IO) {
        val file = File(androidContext.filesDir, fileName)
        file.writeText(content)
    }

    actual suspend fun readText(fileName: String): String? = withContext(Dispatchers.IO) {
        val file = File(androidContext.filesDir, fileName)
        if (file.exists()) file.readText() else null
    }

    actual fun getFilePath(fileName: String): String =
        File(androidContext.filesDir, fileName).absolutePath

    actual fun fileExists(fileName: String): Boolean =
        File(androidContext.filesDir, fileName).exists()

    actual fun getFileSize(fileName: String): Long =
        File(androidContext.filesDir, fileName).length()

    actual fun getCacheDir(): String =
        androidContext.cacheDir.absolutePath

    actual suspend fun writeBytes(fileName: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        File(androidContext.filesDir, fileName).writeBytes(bytes)
    }

    actual suspend fun deleteFile(fileName: String) = withContext(Dispatchers.IO) {
        val file = File(androidContext.filesDir, fileName)
        if (file.exists()) {
            file.delete()
        }
    }
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun formatTime(epochMs: Long): String {
    val date = java.util.Date(epochMs)
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    return sdf.format(date)
}

actual fun formatDateTime(epochMs: Long): String {
    val date = java.util.Date(epochMs)
    val sdf = java.text.SimpleDateFormat("EEE, MMM d, yyyy · h:mm a", java.util.Locale.US)
    return sdf.format(date)
}

actual fun formatDateLabel(epochMs: Long): String {
    val date = java.util.Date(epochMs)
    val sdf = java.text.SimpleDateFormat("EEE, MMM d", java.util.Locale.US)
    return sdf.format(date)
}

actual fun formatCoord(value: Double): String {
    return "%.4f".format(value)
}
