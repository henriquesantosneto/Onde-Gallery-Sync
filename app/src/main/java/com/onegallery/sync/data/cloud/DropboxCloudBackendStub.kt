package com.onegallery.sync.data.cloud

import java.io.File

/** Placeholder Fase 2 — não exposto na UI até OAuth + API estarem prontos. */
class DropboxCloudBackendStub : CloudSyncBackend {
    override val provider: CloudProvider = CloudProvider.DROPBOX

    private fun notReady(): Result<Nothing> = Result.failure(
        UnsupportedOperationException("Dropbox ainda não está disponível nesta versão."),
    )

    override suspend fun ensureSession(): Result<Unit> = notReady()

    override fun syncRootLabel(): String? = null

    override fun isSyncRootConfigured(): Boolean = false

    override suspend fun listRemoteFiles(
        indexBaseline: List<RemoteCloudFile>,
    ): Result<CloudRemoteListing> = notReady()

    override suspend fun uploadLocalFile(
        canonicalPath: String,
        sourceFile: File,
        mimeType: String?,
        onProgress: ((Long, Long?) -> Unit)?,
    ): Result<String> = notReady()

    override suspend fun downloadToFile(
        remoteItemId: String,
        destination: File,
        onProgress: ((Long, Long?) -> Unit)?,
    ): Result<Unit> = notReady()

    override suspend fun downloadItemContentToFile(
        remoteItemId: String,
        destination: File,
    ): Result<Unit> = notReady()

    override suspend fun deleteRemoteItem(remoteItemId: String): Result<Unit> = notReady()
}
