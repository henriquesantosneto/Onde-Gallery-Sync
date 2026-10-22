package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaAlbum
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.PlaceComponents
import com.onegallery.sync.data.local.PlaceMatchKey
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
        val located = metadata.mapNotNull { entity ->
            val label = entity.locationLabel?.trim().orEmpty()
            val components = placeComponentsForEntity(entity)
            when {
                components != null -> {
                    val matchKey = PlaceMatchKey.forComponents(components, entity.locationLabel)
                    Triple(components, matchKey, entity)
                }
                label.isNotEmpty() -> Triple(null, PlaceMatchKey.legacyLabel(label), entity)
                else -> null
            }
        }
        val albums = located
            .groupBy({ it.second.encode() }, { it.third })
            .map { (encodedKey, group) ->
                val sample = group.first()
                val sampleComponents = placeComponentsForEntity(sample)
                    ?: sample.locationLabel?.let { PlaceComponents.fromGeocoderLabel(it) }
                val title = sampleComponents?.hierarchicalDisplayTitle()?.takeIf { it.isNotBlank() }
                    ?: sample.locationLabel.orEmpty()
                val latest = group.maxOfOrNull { it.sortTimestamp() } ?: 0L
                val photoCount = group.count { !GalleryMapper.isVideoFileName(it.displayName) }
                val videoCount = group.size - photoCount
                val coverItems = if (resolveCoverModels && group.isNotEmpty()) {
                    listOf(GalleryMapper.toDisplayItem(group.maxBy { it.sortTimestamp() }, mediaCacheRepository))
                } else {
                    emptyList()
                }
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

    private fun placeComponentsForEntity(entity: SyncMetadataEntity): PlaceComponents? {
        val label = entity.locationLabel?.trim().orEmpty()
        if (label.isEmpty()) return null
        return PlaceComponents.fromGeocoderLabel(label)
    }

    private fun SyncMetadataEntity.sortTimestamp(): Long =
        capturedAtSeconds?.takeIf { it > 0L }
            ?: remoteModifiedAt?.takeIf { it > 0L }
            ?: localModifiedAt?.takeIf { it > 0L }
            ?: 0L
}
