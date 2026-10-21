package com.onegallery.sync.data.cloud

import com.onegallery.sync.sync.SyncMode
import com.onegallery.sync.sync.SyncPreferences

/**
 * Escolhe o backend API ativo (Graph / Dropbox) conforme modo + provedor.
 * Modos legados (SAF, espelho local) continuam fora deste contrato.
 */
class CloudSyncBackendRegistry(
    private val oneDriveGraph: OneDriveGraphCloudBackend,
    private val dropboxStub: DropboxCloudBackendStub,
    private val syncPreferences: SyncPreferences,
) {
    fun activeApiBackend(): CloudSyncBackend? {
        if (syncPreferences.syncMode != SyncMode.ONEDRIVE_GRAPH) return null
        return when (syncPreferences.cloudProvider) {
            CloudProvider.ONEDRIVE -> oneDriveGraph
            CloudProvider.DROPBOX -> dropboxStub
        }
    }

    fun oneDriveGraphBackend(): OneDriveGraphCloudBackend = oneDriveGraph
}
