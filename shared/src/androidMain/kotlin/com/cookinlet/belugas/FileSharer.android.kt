package com.cookinlet.belugas

import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

// Matches the authority declared for the FileProvider in AndroidManifest.xml.
private const val FILE_PROVIDER_AUTHORITY = "com.cookinlet.belugas.fileprovider"

actual class FileSharer actual constructor() {
    actual suspend fun share(fileName: String, mimeType: String, bytes: ByteArray) = withContext(Dispatchers.IO) {
        // FileProvider only grants access to files under the roots declared in
        // res/xml/file_paths.xml -- "exports/" there matches this subdirectory.
        val exportsDir = File(androidContext.cacheDir, "exports").apply { mkdirs() }
        val file = File(exportsDir, fileName)
        file.writeBytes(bytes)

        val uri = FileProvider.getUriForFile(androidContext, FILE_PROVIDER_AUTHORITY, file)
        val sendIntent = Intent(Intent.ACTION_SEND).apply {
            type = mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val chooser = Intent.createChooser(sendIntent, "Save or share export").apply {
            // androidContext is the Application context, not an Activity -- launching from it
            // requires a fresh task.
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        androidContext.startActivity(chooser)
    }
}
