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

    fun matchesRoot(itemId: String, driveRelativePath: String): Boolean {
        val path = driveRelativePath.trim('/')
        return syncRootItemId == itemId && syncRootDrivePath?.trim('/') == path
    }

    fun setSyncRoot(itemId: String, driveRelativePath: String, label: String) {
        val previousItem = syncRootItemId
        val previousPath = syncRootDrivePath?.trim('/')
        val nextPath = driveRelativePath.trim('/')
        prefs.edit()
            .putString(KEY_ITEM_ID, itemId)
            .putString(KEY_DRIVE_PATH, nextPath)
            .putString(KEY_LABEL, label)
            .apply()
        if (previousItem != itemId || previousPath != nextPath) {
            clearDriveDeltaLink()
        }
    }

    /** Token @odata.deltaLink do Graph para listagem incremental da pasta de sync. */
    var driveDeltaLink: String?
        get() = prefs.getString(KEY_DRIVE_DELTA_LINK, null)
        set(value) = prefs.edit().putString(KEY_DRIVE_DELTA_LINK, value?.trim()?.ifBlank { null }).apply()

    fun clearDriveDeltaLink() {
        prefs.edit().remove(KEY_DRIVE_DELTA_LINK).apply()
    }

    fun clear() {
        prefs.edit().clear().apply()
    }

    companion object {
        private const val KEY_ITEM_ID = "sync_root_item_id"
        private const val KEY_DRIVE_PATH = "sync_root_drive_path"
        private const val KEY_LABEL = "sync_root_label"
        private const val KEY_DRIVE_DELTA_LINK = "sync_root_drive_delta_link"
    }
}
