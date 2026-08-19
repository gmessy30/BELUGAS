package com.cookinlet.belugas

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.Foundation.NSData
import platform.Foundation.dataWithContentsOfFile
import platform.posix.memcpy
import org.jetbrains.skia.Image as SkiaImage

@OptIn(ExperimentalForeignApi::class)
@Composable
actual fun LocalPhotoPreview(
    filePath: String,
    modifier: Modifier
) {
    val imageBitmap = remember(filePath) {
        try {
            val data = platform.Foundation.NSData.dataWithContentsOfFile(filePath)
            data?.let {
                val bytes = ByteArray(it.length.toInt())
                it.bytes?.let { ptr ->
                    platform.posix.memcpy(bytes.usePinned { pinned -> pinned.addressOf(0) }, ptr, it.length)
                }
                SkiaImage.makeFromEncoded(bytes).toComposeImageBitmap()
            }
        } catch (e: Exception) {
            null
        }
    }

    imageBitmap?.let {
        Image(
            bitmap = it,
            contentDescription = "Captured Whale Photo",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}
