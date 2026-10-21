package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.AlbumGroupKeys

/** Alias na UI — mesma regra que [AlbumGroupKeys] no índice. */
object GalleryAlbumKeys {
    fun forEntity(entity: SyncMetadataEntity): String = AlbumGroupKeys.fromEntity(entity)

    fun forDisplayItem(item: GalleryDisplayItem): String = item.albumName
}
