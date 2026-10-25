package com.onegallery.sync.ui.gallery

import android.graphics.Bitmap
import android.graphics.Color
import java.io.File

fun galleryTestJpeg(): File {
    val file = File.createTempFile("og-thumb", ".jpg")
    val bitmap = Bitmap.createBitmap(40, 40, Bitmap.Config.RGB_565)
    bitmap.eraseColor(Color.BLUE)
    file.outputStream().use { out ->
        bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
    }
    bitmap.recycle()
    return file
}
