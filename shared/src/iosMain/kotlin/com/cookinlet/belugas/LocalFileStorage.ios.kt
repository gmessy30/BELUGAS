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

actual fun formatTime12Hour(epochMs: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    val formatter = NSDateFormatter().apply {
        dateFormat = "h:mm a"
        // Kenai gate times are meaningless in the device's own zone -- someone outside Alaska
        // checking the banner needs the entrance-gate time AT the river, not translated to
        // wherever they're standing. Explicit, not inherited, same reasoning as
        // anchorageMonthDay's own zone.
        timeZone = NSTimeZone.timeZoneWithName("America/Anchorage")!!
        // Without this, the formatter falls back to the device locale -- under a locale with a
        // non-Latin script or non-Western digits, stringFromDate could return "AM"/"PM" in that
        // script or non-ASCII numerals instead of the plain "3:42 PM" the banner copy expects.
        // en_US_POSIX guarantees plain ASCII, matching Android's Locale.US.
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
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

actual fun anchorageMonthDay(epochMs: Long): String {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    val formatter = NSDateFormatter().apply {
        dateFormat = "MM-dd"
        timeZone = NSTimeZone.timeZoneWithName("America/Anchorage")!!
        // Without this, the formatter falls back to the device locale -- under a locale with
        // non-Western digits, stringFromDate returns non-ASCII numerals, breaking the
        // lexicographic MM-DD comparison in isKenaiInSeasonLocally. en_US_POSIX guarantees
        // plain ASCII digits, matching Android's Locale.US.
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
    }
    return formatter.stringFromDate(date)
}

// Built on NSDateFormatter both directions (stringFromDate here, dateFromString below) rather
// than NSCalendar/NSDateComponents -- deliberately reusing the exact same class/property pattern
// (dateFormat/timeZone/locale) anchorageMonthDay above already relies on, instead of introducing
// an unrelated API this codebase has never used before.
actual fun anchorageDateParts(epochMs: Long): Triple<Int, Int, Int> {
    val date = NSDate.dateWithTimeIntervalSince1970(epochMs / 1000.0)
    val formatter = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd"
        timeZone = NSTimeZone.timeZoneWithName("America/Anchorage")!!
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
    }
    val (y, m, d) = formatter.stringFromDate(date).split("-").map { it.toInt() }
    return Triple(y, m, d)
}

actual fun anchorageMidnightEpochMs(year: Int, month: Int, day: Int): Long {
    val formatter = NSDateFormatter().apply {
        dateFormat = "yyyy-MM-dd HH:mm:ss"
        timeZone = NSTimeZone.timeZoneWithName("America/Anchorage")!!
        locale = NSLocale(localeIdentifier = "en_US_POSIX")
    }
    val dateString = "${year.toString().padStart(4, '0')}-${month.toString().padStart(2, '0')}-${day.toString().padStart(2, '0')} 00:00:00"
    val date = formatter.dateFromString(dateString)!!
    return (date.timeIntervalSince1970 * 1000).toLong()
}
