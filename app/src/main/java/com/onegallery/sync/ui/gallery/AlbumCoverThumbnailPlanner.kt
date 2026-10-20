package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.PlaceComponents
import com.onegallery.sync.data.local.PlaceMatchKey
import com.onegallery.sync.data.local.db.SyncMetadataEntity
/** Sync: no máximo um item por álbum ainda sem capa (prioridade); demais miniaturas no prefetch geral. */
object AlbumCoverThumbnailPlanner {

    fun coverPathsNeedingThumbnail(
        entities: List<SyncMetadataEntity>,
        mediaCacheRepository: MediaCacheRepository,
    ): List<String> {
        if (entities.isEmpty()) return emptyList()
        val items = entities.map { GalleryMapper.toDisplayItem(it, mediaCacheRepository) }
        val paths = linkedSetOf<String>()
        items.groupBy { it.albumName }.values.forEach { group ->
            addCoverPathIfNeeded(group, mediaCacheRepository, paths)
        }
        placeGroups(items).values.forEach { group ->
            addCoverPathIfNeeded(group, mediaCacheRepository, paths)
        }
        return paths.toList()
    }

    private fun placeGroups(items: List<GalleryDisplayItem>): Map<String, List<GalleryDisplayItem>> {
        val located = items.mapNotNull { item ->
            val label = item.locationLabel?.trim().orEmpty()
            val components = PlaceComponents.forItem(item)
                ?: label.takeIf { it.isNotEmpty() }?.let { PlaceComponents.fromGeocoderLabel(it) }
            val key = when {
                components != null -> PlaceMatchKey.forComponents(components, item.locationLabel).encode()
                label.isNotEmpty() -> PlaceMatchKey.legacyLabel(label).encode()
                else -> null
            }
            key?.let { it to item }
        }
        return located.groupBy({ it.first }, { it.second })
    }

    private fun addCoverPathIfNeeded(
        group: List<GalleryDisplayItem>,
        mediaCacheRepository: MediaCacheRepository,
        paths: MutableSet<String>,
    ) {
        if (group.isEmpty()) return
        if (resolveAlbumCoverItem(group) != null) return
        val candidate = group
            .asSequence()
            .sortedByDescending { it.sortTimestamp }
            .firstOrNull { canBuildThumbnail(it) }
            ?: return
        if (mediaCacheRepository.thumbnailIfCached(candidate.canonicalPath) == null) {
            paths.add(candidate.canonicalPath)
        }
    }

    private fun canBuildThumbnail(item: GalleryDisplayItem): Boolean {
        if (item.oneDriveItemId != null) return true
        if (item.localMediaId != null) return true
        if (item.localUri != null) return true
        if (item.cachedOriginal?.isFile == true) return true
        return false
    }
}
