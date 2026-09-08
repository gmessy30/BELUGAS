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

    actual suspend fun readBytesAtPath(absolutePath: String): ByteArray? = withContext(Dispatchers.IO) {
        val file = File(absolutePath)
        if (file.exists()) file.readBytes() else null
    }
}

actual fun currentTimeMillis(): Long = System.currentTimeMillis()

actual fun formatTime(epochMs: Long): String {
    val date = java.util.Date(epochMs)
    val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.US)
    return sdf.format(date)
}

actual fun formatTime12Hour(epochMs: Long): String {
    val date = java.util.Date(epochMs)
    val sdf = java.text.SimpleDateFormat("h:mm a", java.util.Locale.US)
    // Kenai gate times are meaningless in the device's own zone -- someone outside Alaska
    // checking the banner needs the entrance-gate time AT the river, not translated to wherever
    // they're standing. Explicit, not inherited, same reasoning as anchorageMonthDay's own zone.
    sdf.timeZone = java.util.TimeZone.getTimeZone("America/Anchorage")
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

actual fun formatIso8601Utc(epochMs: Long): String {
    val date = java.util.Date(epochMs)
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
    return sdf.format(date)
}

actual fun anchorageMonthDay(epochMs: Long): String {
    val date = java.util.Date(epochMs)
    val sdf = java.text.SimpleDateFormat("MM-dd", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("America/Anchorage")
    return sdf.format(date)
}

actual fun anchorageDateParts(epochMs: Long): Triple<Int, Int, Int> {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("America/Anchorage")
    val (y, m, d) = sdf.format(java.util.Date(epochMs)).split("-").map { it.toInt() }
    return Triple(y, m, d)
}

actual fun anchorageMidnightEpochMs(year: Int, month: Int, day: Int): Long {
    val sdf = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US)
    sdf.timeZone = java.util.TimeZone.getTimeZone("America/Anchorage")
    val dateString = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')} 00:00:00"
    return sdf.parse(dateString)!!.time
}
