package com.onegallery.sync.data.onedrive

import android.content.Intent
import android.net.Uri

object FolderPickerResults {
    fun uriFromResult(data: Intent?): Uri? {
        if (data == null) return null
        data.data?.let { return it }
        val clip = data.clipData
        if (clip != null && clip.itemCount > 0) {
            clip.getItemAt(0).uri?.let { return it }
        }
        return null
    }
}
