package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.data.local.db.UserAlbumEntity

/**
 * Monta listas de álbuns a partir do índice Room — mesma regra de miniatura da grade Fotos.
 */
object GalleryAlbumIndexBuilder {

    fun buildFolderAlbums(
        metadata: List<SyncMetadataEntity>,
        userAlbums: List<UserAlbumEntity>,
        sort: AlbumListSort,
        albumCoverLocalIndex: AlbumCoverLocalIndex,
        mediaCacheRepository: MediaCacheRepository,
        localMediaRepository: LocalMediaRepository,
        onlyItemsWithThumbnail: Boolean,
        resolveCoverModels: Boolean,
    ): List<MediaAlbum> {
        if (metadata.isEmpty()) return emptyList()
        val browsable = mapMetadataToBrowsableDisplayItems(
            metadata,
            localMediaRepository,
            mediaCacheRepository,
            onlyItemsWithThumbnail,
        )
        return buildAlbums(
            mirrored = browsable,
            userAlbums = userAlbums,
            sort = sort,
            albumCoverLocalIndex = albumCoverLocalIndex,
            mediaCacheRepository = mediaCacheRepository,
            resolveCoverModels = resolveCoverModels,
        )
    }

    fun buildPlaceAlbums(
        metadata: List<SyncMetadataEntity>,
        sort: AlbumListSort,
        albumCoverLocalIndex: AlbumCoverLocalIndex,
        mediaCacheRepository: MediaCacheRepository,
        localMediaRepository: LocalMediaRepository,
        onlyItemsWithThumbnail: Boolean,
        resolveCoverModels: Boolean,
    ): List<MediaAlbum> {
        val browsable = mapMetadataToBrowsableDisplayItems(
            metadata,
            localMediaRepository,
            mediaCacheRepository,
            onlyItemsWithThumbnail,
        )
        return sortPlaceAlbums(
            buildPlaceAlbums(
                items = browsable,
                albumCoverLocalIndex = albumCoverLocalIndex,
                mediaCacheRepository = mediaCacheRepository,
                resolveCoverModels = resolveCoverModels,
            ),
            sort,
        )
    }
}
