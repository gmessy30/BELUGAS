package com.cookinlet.belugas

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import platform.Foundation.*
import platform.UIKit.UIActivityViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIViewController

actual class FileSharer actual constructor() {
    @OptIn(ExperimentalForeignApi::class)
    actual suspend fun share(fileName: String, mimeType: String, bytes: ByteArray): Unit = withContext(Dispatchers.Main) {
        val path = NSTemporaryDirectory() + fileName
        val data = bytes.usePinned { NSData.dataWithBytes(it.addressOf(0), bytes.size.toULong()) }
        data.writeToFile(path, true)
        val url = NSURL.fileURLWithPath(path)

        val activityController = UIActivityViewController(activityItems = listOf(url), applicationActivities = null)
        val top = topViewController()
        if (top != null) {
            top.presentViewController(activityController, animated = true, completion = null)
        }
    }

    private fun topViewController(): UIViewController? {
        var top = UIApplication.sharedApplication.keyWindow?.rootViewController
        while (top?.presentedViewController != null) {
            top = top.presentedViewController
        }
        return top
    }
}
