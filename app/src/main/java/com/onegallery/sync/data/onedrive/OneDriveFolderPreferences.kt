package com.onegallery.sync.data.onedrive

import android.content.Context
import android.net.Uri

class OneDriveFolderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("onegallery_onedrive_folder", Context.MODE_PRIVATE)

    var treeUri: Uri?
        get() = prefs.getString(KEY_TREE_URI, null)?.let { Uri.parse(it) }
        set(value) = prefs.edit().putString(KEY_TREE_URI, value?.toString()).apply()

    var displayName: String?
        get() = prefs.getString(KEY_DISPLAY_NAME, null)
        set(value) = prefs.edit().putString(KEY_DISPLAY_NAME, value).apply()

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_TREE_URI = "tree_uri"
        private const val KEY_DISPLAY_NAME = "display_name"
    }
}
