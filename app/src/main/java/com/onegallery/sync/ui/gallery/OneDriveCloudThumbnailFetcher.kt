package com.onegallery.sync.ui.gallery

import coil.ImageLoader
import coil.decode.DataSource
import coil.decode.ImageSource
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.fetch.SourceResult
import coil.request.Options
import com.onegallery.sync.OneGalleryApplication
import com.onegallery.sync.data.local.VideoThumbnailHelper
import com.onegallery.sync.data.onedrive.CloudThumbnailStaging
import com.onegallery.sync.data.onedrive.CloudVideoThumbnailLoader
import okio.Path.Companion.toOkioPath
import java.io.File

/** Coil: JPEG em disco ou poster Graph (foto e vídeo). Commit no cache para a célula não depender da rede de novo. */
class OneDriveCloudThumbnailFetcher(
    private val key: OneDriveCloudThumbnailKey,
    private val app: OneGalleryApplication,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val isVideo = GalleryMapper.isVideoFileName(key.canonicalPath)
        val cached = if (isVideo) {
            app.mediaCacheRepository.galleryThumbnailResolved(
                key.canonicalPath,
                key.oneDriveItemId,
                requireVideoAcceptable = true,
                allowCloudVideoPoster = true,
            )
        } else {
            app.mediaCacheRepository.displayableThumbnailIfCached(
                key.canonicalPath,
                key.oneDriveItemId,
            )
        }
        if (cached != null) {
            return SourceResult(
                source = ImageSource(file = cached.toOkioPath()),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            )
        }
        val staging = File(
            app.cacheDir,
            "coil-cloud-${key.oneDriveItemId.hashCode()}.jpg",
        )
        staging.delete()
        val displayFile = if (isVideo) {
            fetchVideo(staging)
        } else {
            fetchImage(staging)
        }
        return SourceResult(
            source = ImageSource(file = displayFile.toOkioPath()),
            mimeType = "image/jpeg",
            dataSource = DataSource.NETWORK,
        )
    }

    private suspend fun fetchImage(staging: File): File {
        val downloaded = app.oneDriveRepository.downloadThumbnailToFile(
            key.oneDriveItemId,
            staging,
            onProgress = null,
        ).isSuccess && CloudThumbnailStaging.acceptDownloadedCloudThumbnail(
            staging,
            app.mediaCacheRepository,
            isVideo = false,
        )
        if (!downloaded) {
            staging.delete()
            error("Miniatura Graph indisponível para ${key.oneDriveItemId}")
        }
        return app.mediaCacheRepository.commitThumbnail(
            staging,
            key.canonicalPath,
            key.oneDriveItemId,
        ) ?: error("Falha ao gravar miniatura ${key.canonicalPath}")
    }

    private suspend fun fetchVideo(staging: File): File {
        val remoteSizeBytes = app.database.syncMetadataDao()
            .getByCanonicalPath(key.canonicalPath)
            ?.remoteSizeBytes
        val loaded = CloudVideoThumbnailLoader.loadIntoFile(
            repository = app.oneDriveRepository,
            mediaCacheRepository = app.mediaCacheRepository,
            itemId = key.oneDriveItemId,
            staging = staging,
            remoteSizeBytes = remoteSizeBytes,
            aggressive = false,
            onProgress = null,
        )
        val fromGraphPoster = loaded == CloudVideoThumbnailLoader.Outcome.GRAPH_POSTER
        val stagingOk = loaded != CloudVideoThumbnailLoader.Outcome.NONE &&
            when {
                fromGraphPoster -> VideoThumbnailHelper.isCloudPosterThumbnailFile(staging)
                else -> VideoThumbnailHelper.isDisplayableVideoThumbnailFile(staging)
            }
        if (!stagingOk) {
            staging.delete()
            error("Miniatura Graph/vídeo indisponível para ${key.oneDriveItemId}")
        }
        return CloudThumbnailStaging.commitGalleryVideoThumbnailIfValid(
            staging,
            app.mediaCacheRepository,
            key.canonicalPath,
            key.oneDriveItemId,
            fromGraphPoster,
        ) ?: run {
            staging.delete()
            error("Miniatura Graph/vídeo indisponível para ${key.oneDriveItemId}")
        }
    }

    class Factory(
        private val app: OneGalleryApplication,
    ) : Fetcher.Factory<OneDriveCloudThumbnailKey> {
        override fun create(
            data: OneDriveCloudThumbnailKey,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher = OneDriveCloudThumbnailFetcher(data, app)
    }
}
