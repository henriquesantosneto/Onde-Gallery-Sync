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

class OneDriveCloudThumbnailFetcher(
    private val key: OneDriveCloudThumbnailKey,
    private val app: OneGalleryApplication,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        app.mediaCacheRepository.galleryThumbnailResolved(
            key.canonicalPath,
            key.oneDriveItemId,
            requireVideoAcceptable = true,
        )?.let { cached ->
            return SourceResult(
                source = ImageSource(file = cached.toOkioPath()),
                mimeType = "image/jpeg",
                dataSource = DataSource.DISK,
            )
        }
        val staging = File(
            app.cacheDir,
            "coil-cloud-vid-${key.oneDriveItemId.hashCode()}.jpg",
        )
        staging.delete()
        val remoteSizeBytes = app.database.syncMetadataDao()
            .getByCanonicalPath(key.canonicalPath)
            ?.remoteSizeBytes
        val loaded = CloudVideoThumbnailLoader.loadIntoFile(
            repository = app.oneDriveRepository,
            mediaCacheRepository = app.mediaCacheRepository,
            itemId = key.oneDriveItemId,
            staging = staging,
            remoteSizeBytes = remoteSizeBytes,
            aggressive = true,
            onProgress = null,
        )
        val stagingOk = loaded != CloudVideoThumbnailLoader.Outcome.NONE &&
            VideoThumbnailHelper.isDisplayableVideoThumbnailFile(staging)
        if (!stagingOk) {
            staging.delete()
            error("Miniatura Graph/vídeo indisponível para ${key.oneDriveItemId}")
        }
        val fromGraphPoster = loaded == CloudVideoThumbnailLoader.Outcome.GRAPH_POSTER
        val displayFile = CloudThumbnailStaging.commitGalleryVideoThumbnailIfValid(
            staging,
            app.mediaCacheRepository,
            key.canonicalPath,
            key.oneDriveItemId,
            fromGraphPoster,
        ) ?: staging
        return SourceResult(
            source = ImageSource(file = displayFile.toOkioPath()),
            mimeType = "image/jpeg",
            dataSource = if (fromGraphPoster) DataSource.NETWORK else DataSource.DISK,
        )
    }

    class Factory(
        private val app: OneGalleryApplication,
    ) : Fetcher.Factory<OneDriveCloudThumbnailKey> {
        override fun create(
            data: OneDriveCloudThumbnailKey,
            options: Options,
            imageLoader: ImageLoader,
        ): Fetcher {
            return OneDriveCloudThumbnailFetcher(data, app)
        }
    }
}
