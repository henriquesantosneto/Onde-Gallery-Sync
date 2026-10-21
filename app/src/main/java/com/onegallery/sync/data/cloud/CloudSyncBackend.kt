package com.onegallery.sync.data.cloud

import java.io.File

/**
 * Contrato neutro de sync na nuvem (Fase 1 multicloud).
 * Implementações: [OneDriveGraphCloudBackend], futuro Dropbox.
 */
interface CloudSyncBackend {
    val provider: CloudProvider

    suspend fun ensureSession(): Result<Unit>

    fun syncRootLabel(): String?

    fun isSyncRootConfigured(): Boolean

    suspend fun listRemoteFiles(indexBaseline: List<RemoteCloudFile>): Result<CloudRemoteListing>

    suspend fun uploadLocalFile(
        canonicalPath: String,
        sourceFile: File,
        mimeType: String?,
        onProgress: ((Long, Long?) -> Unit)?,
    ): Result<String>

    suspend fun downloadToFile(
        remoteItemId: String,
        destination: File,
        onProgress: ((Long, Long?) -> Unit)?,
    ): Result<Unit>

    suspend fun downloadItemContentToFile(remoteItemId: String, destination: File): Result<Unit>

    suspend fun deleteRemoteItem(remoteItemId: String): Result<Unit>
}
