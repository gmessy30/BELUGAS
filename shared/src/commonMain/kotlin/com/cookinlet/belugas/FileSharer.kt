package com.cookinlet.belugas

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember

/**
 * Hands a finished in-memory file to the OS share sheet (Android's chooser / iOS's
 * UIActivityViewController) so the user can save or send it -- there was no "get a file off
 * the device" mechanism anywhere in the app before the data-export feature needed one.
 */
expect class FileSharer() {
    suspend fun share(fileName: String, mimeType: String, bytes: ByteArray)
}

@Composable
fun rememberFileSharer(): FileSharer = remember { FileSharer() }
