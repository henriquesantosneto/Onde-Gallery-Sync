package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.placeAlbumBucketEncodedKey
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.SyncPaths

/** Chaves de capa na grade (`folder:…` / `place:…`) afetadas por um item do índice. */
object AlbumCoverBucketKeys {
    fun forEntity(entity: SyncMetadataEntity): Set<String> {
        val keys = linkedSetOf<String>()
        keys.add("album:${albumGroupKey(entity)}")
        placeAlbumBucketEncodedKey(entity)?.let { keys.add("place:$it") }
        return keys
    }

    fun forDisplayItem(item: GalleryDisplayItem): Set<String> {
        val keys = linkedSetOf<String>()
        keys.add("album:${item.albumName}")
        placeAlbumBucketEncodedKey(item)?.let { keys.add("place:$it") }
        return keys
    }

    fun albumGroupKey(entity: SyncMetadataEntity): String {
        return com.onegallery.sync.sync.AlbumGroupKeys.fromEntity(entity)
    }
}
