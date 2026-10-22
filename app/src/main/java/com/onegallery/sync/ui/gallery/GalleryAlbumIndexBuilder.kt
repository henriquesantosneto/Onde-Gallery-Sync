package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.groupMetadataForPlaceAlbums
import com.onegallery.sync.data.local.placeAlbumTitleForGroup
import com.onegallery.sync.data.local.placeAlbumTitleForMetadataGroup
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.data.local.db.UserAlbumEntity
import com.onegallery.sync.sync.SyncPaths

/**
 * Monta listas de álbuns a partir do índice Room — sem esperar [GalleryDisplayItem] da biblioteca inteira.
 */
object GalleryAlbumIndexBuilder {

    fun buildFolderAlbums(
        metadata: List<SyncMetadataEntity>,
        userAlbums: List<UserAlbumEntity>,
        sort: AlbumListSort,
        albumCoverLocalIndex: AlbumCoverLocalIndex,
        mediaCacheRepository: MediaCacheRepository,
        resolveCoverModels: Boolean,
    ): List<MediaAlbum> {
        if (metadata.isEmpty()) return emptyList()
        val grouped = metadata.groupBy { GalleryAlbumKeys.forEntity(it) }
        val titles = (userAlbums.map { it.title } + grouped.keys).distinct()
        val albums = titles.map { title ->
            val items = grouped[title].orEmpty()
            val latest = items.maxOfOrNull { it.sortTimestamp() } ?: 0L
            val userMeta = userAlbums.find { it.title == title }
            val photoCount = items.count { !GalleryMapper.isVideoFileName(it.displayName) }
            val videoCount = items.size - photoCount
            val coverItems = if (resolveCoverModels && items.isNotEmpty()) {
                listOf(GalleryMapper.toDisplayItem(items.maxBy { it.sortTimestamp() }, mediaCacheRepository))
            } else {
                emptyList()
            }
            MediaAlbum(
                bucketId = "album:$title",
                title = if (userMeta != null) title else SyncPaths.albumListDisplayName(title),
                filterKey = title,
                itemCount = items.size,
                photoCount = photoCount,
                videoCount = videoCount,
                coverModel = if (resolveCoverModels && coverItems.isNotEmpty()) {
                    albumCoverLocalIndex.coverModelForKey(
                        cacheKey = "album:$title",
                        items = coverItems,
                        mediaCacheRepository = mediaCacheRepository,
                    )
                } else {
                    null
                },
                sortTimestamp = latest,
                isUserManaged = userMeta != null,
                userAlbumId = userMeta?.id,
                createdAtEpoch = userMeta?.createdAtEpoch,
            )
        }
        return sortMediaAlbums(albums, sort)
    }

    fun buildPlaceAlbums(
        metadata: List<SyncMetadataEntity>,
        sort: AlbumListSort,
        albumCoverLocalIndex: AlbumCoverLocalIndex,
        mediaCacheRepository: MediaCacheRepository,
        resolveCoverModels: Boolean,
    ): List<MediaAlbum> {
        val albums = groupMetadataForPlaceAlbums(metadata).map { (encodedKey, group) ->
            val displayGroup = group.map { GalleryMapper.toDisplayItem(it, mediaCacheRepository) }
            val coverItem = if (resolveCoverModels) {
                resolveAlbumCoverItem(displayGroup, mediaCacheRepository)
            } else {
                null
            }
            val title = if (coverItem != null) {
                placeAlbumTitleForGroup(displayGroup, coverItem)
            } else {
                placeAlbumTitleForMetadataGroup(group)
            }
            val latest = group.maxOfOrNull { it.sortTimestamp() } ?: 0L
            val photoCount = group.count { !GalleryMapper.isVideoFileName(it.displayName) }
            val videoCount = group.size - photoCount
            val coverItems = coverItem?.let { listOf(it) }.orEmpty()
            MediaAlbum(
                bucketId = "place:$encodedKey",
                title = title,
                itemCount = group.size,
                photoCount = photoCount,
                videoCount = videoCount,
                coverModel = if (resolveCoverModels && coverItems.isNotEmpty()) {
                    albumCoverLocalIndex.coverModelForKey(
                        cacheKey = "place:$encodedKey",
                        items = coverItems,
                        mediaCacheRepository = mediaCacheRepository,
                    )
                } else {
                    null
                },
                sortTimestamp = latest,
            )
        }
        return sortPlaceAlbums(albums, sort)
    }

    private fun SyncMetadataEntity.sortTimestamp(): Long =
        capturedAtSeconds?.takeIf { it > 0L }
            ?: remoteModifiedAt?.takeIf { it > 0L }
            ?: localModifiedAt?.takeIf { it > 0L }
            ?: 0L
}
