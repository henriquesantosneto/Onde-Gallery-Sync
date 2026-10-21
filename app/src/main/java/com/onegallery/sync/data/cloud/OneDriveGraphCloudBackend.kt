package com.onegallery.sync.data.cloud

import com.onegallery.sync.data.onedrive.OneDriveRepository
import java.io.File

/** Adapter Graph → [CloudSyncBackend] (comportamento idêntico ao modo ONEDRIVE_GRAPH atual). */
class OneDriveGraphCloudBackend(
    private val oneDriveRepository: OneDriveRepository,
) : CloudSyncBackend {
    override val provider: CloudProvider = CloudProvider.ONEDRIVE

    override suspend fun ensureSession(): Result<Unit> = oneDriveRepository.ensureGraphSession()

    override fun syncRootLabel(): String? = oneDriveRepository.syncRootLabel()

    override fun isSyncRootConfigured(): Boolean = oneDriveRepository.isSyncRootConfigured()

    override suspend fun listRemoteFiles(indexBaseline: List<RemoteCloudFile>): Result<CloudRemoteListing> {
        val baseline = indexBaseline.map { it.toRemoteDriveFile() }
        return oneDriveRepository.listMirrorFiles(baseline).map { listing ->
            CloudRemoteListing(
                files = listing.files.map { it.toRemoteCloudFile() },
                incremental = listing.incremental,
                deltaChangeCount = listing.deltaChangeCount,
                unresolvedDeltaSkips = listing.unresolvedDeltaSkips,
                supplementalFromRecursive = listing.supplementalFromRecursive,
            )
        }
    }

    override suspend fun uploadLocalFile(
        canonicalPath: String,
        sourceFile: File,
        mimeType: String?,
        onProgress: ((Long, Long?) -> Unit)?,
    ): Result<String> {
        return oneDriveRepository.uploadLocalFile(
            canonicalPath,
            sourceFile,
            mimeType,
            onProgress,
        ).map { item ->
            item.id ?: error("Upload concluído sem id remoto")
        }
    }

    override suspend fun downloadToFile(
        remoteItemId: String,
        destination: File,
        onProgress: ((Long, Long?) -> Unit)?,
    ): Result<Unit> = oneDriveRepository.downloadToFile(remoteItemId, destination, onProgress)

    override suspend fun downloadItemContentToFile(
        remoteItemId: String,
        destination: File,
    ): Result<Unit> = oneDriveRepository.downloadItemContentToFile(remoteItemId, destination)

    override suspend fun deleteRemoteItem(remoteItemId: String): Result<Unit> =
        oneDriveRepository.deleteRemoteItem(remoteItemId)
}
