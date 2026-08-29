package com.cookinlet.belugas

import platform.Foundation.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.cinterop.*
import platform.posix.memcpy

actual class LocalFileStorage actual constructor() {

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun writeText(fileName: String, content: String): Unit = withContext(Dispatchers.Default) {
        val filePath = getDocumentsDirectory() + "/" + fileName
        (content as NSString).writeToFile(filePath, true, NSUTF8StringEncoding, null)
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun readText(fileName: String): String? = withContext(Dispatchers.Default) {
        val filePath = getDocumentsDirectory() + "/" + fileName
        val fileManager = NSFileManager.defaultManager
        if (fileManager.fileExistsAtPath(filePath)) {
            NSString.stringWithContentsOfFile(filePath, NSUTF8StringEncoding, null) as String?
        } else {
            null
        }
    }

    actual fun getFilePath(fileName: String): String =
        getDocumentsDirectory() + "/" + fileName

    actual fun fileExists(fileName: String): Boolean =
        NSFileManager.defaultManager.fileExistsAtPath(getFilePath(fileName))

    @OptIn(ExperimentalForeignApi::class)
    actual fun getFileSize(fileName: String): Long {
        val path = getFilePath(fileName)
        val fileManager = NSFileManager.defaultManager
        if (!fileManager.fileExistsAtPath(path)) return 0L
        
        val attributes = fileManager.attributesOfItemAtPath(path, null)
        return attributes?.get(NSFileSize)?.let { (it as NSNumber).longLongValue } ?: 0L
    }

    actual fun getCacheDir(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSCachesDirectory, NSUserDomainMask, true)
        return paths.first() as String
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun writeBytes(fileName: String, bytes: ByteArray): Unit = withContext(Dispatchers.Default) {
        val path = getFilePath(fileName)
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        data.writeToFile(path, true)
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun deleteFile(fileName: String): Unit = withContext(Dispatchers.Default) {
        val path = getFilePath(fileName)
        val fileManager = NSFileManager.defaultManager
        if (fileManager.fileExistsAtPath(path)) {
            fileManager.removeItemAtPath(path, null)
        }
    }

    private fun getDocumentsDirectory(): String {
        val paths = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true)
        return paths.first() as String
    }

    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun readBytesAtPath(absolutePath: String): ByteArray? = withContext(Dispatchers.Default) {
        val data = NSData.dataWithContentsOfFile(absolutePath) ?: return@withContext null
        val bytes = ByteArray(data.length.toInt())
        data.bytes?.let { ptr ->
            bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), ptr, data.length) }
        }
        bytes
    }
}

actual fun currentTimeMillis(): Long = (NSDate().timeIntervalSince1970 * 1000).toLong()

actual fun formatTime(epochMs: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    val formatter = NSDateFormatter().apply {
        dateFormat = "HH:mm"
    }
    return formatter.stringFromDate(date)
}

actual fun formatDateTime(epochMs: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    val formatter = NSDateFormatter().apply {
        dateFormat = "EEE, MMM d, yyyy · h:mm a"
    }
    return formatter.stringFromDate(date)
}

actual fun formatDateLabel(epochMs: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    val formatter = NSDateFormatter().apply {
        dateFormat = "EEE, MMM d"
    }
    return formatter.stringFromDate(date)
}

@OptIn(ExperimentalForeignApi::class)
actual fun formatCoord(value: Double): String {
    return NSString.stringWithFormat("%.4f", value)
}

actual fun formatIso8601Utc(epochMs: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    val formatter = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd'T'HH:mm:ss'Z'"
        timeZone = NSTimeZone.timeZoneForSecondsFromGMT(0)
    }
    return formatter.stringFromDate(date)
}
