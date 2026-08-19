package com.cookinlet.belugas

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale

import java.io.File

@Composable
actual fun LocalPhotoPreview(
    filePath: String,
    modifier: Modifier
) {
    val bitmap = remember(filePath) {
        val file = File(filePath)
        if (file.exists()) {
            BitmapFactory.decodeFile(file.absolutePath)
        } else {
            null
        }
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = "Captured Whale Photo",
            contentScale = ContentScale.Crop,
            modifier = modifier
        )
    }
}
