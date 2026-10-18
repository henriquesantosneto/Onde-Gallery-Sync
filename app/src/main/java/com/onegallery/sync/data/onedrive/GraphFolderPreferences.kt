package com.onegallery.sync.data.onedrive

import android.content.Context

/** Pasta raiz escolhida no OneDrive (nuvem) para sync via Microsoft Graph. */
class GraphFolderPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("onegallery_graph_folder", Context.MODE_PRIVATE)

    var syncRootItemId: String?
        get() = prefs.getString(KEY_ITEM_ID, null)
        set(value) = prefs.edit().putString(KEY_ITEM_ID, value).apply()

    /** Caminho relativo à raiz do drive, ex.: `Fotos/MinhaGaleria` (sem barra inicial). */
    var syncRootDrivePath: String?
        get() = prefs.getString(KEY_DRIVE_PATH, null)
        set(value) = prefs.edit().putString(KEY_DRIVE_PATH, value?.trim('/')?.ifBlank { null }).apply()

    var displayLabel: String?
        get() = prefs.getString(KEY_LABEL, null)
        set(value) = prefs.edit().putString(KEY_LABEL, value).apply()

    fun isConfigured(): Boolean = !syncRootItemId.isNullOrBlank()

    fun setSyncRoot(itemId: String, driveRelativePath: String, label: String) {
        prefs.edit()
            .putString(KEY_ITEM_ID, itemId)
            .putString(KEY_DRIVE_PATH, driveRelativePath.trim('/'))
            .putString(KEY_LABEL, label)
            .apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ITEM_ID = "sync_root_item_id"
        private const val KEY_DRIVE_PATH = "sync_root_drive_path"
        private const val KEY_LABEL = "sync_root_label"
    }
}
