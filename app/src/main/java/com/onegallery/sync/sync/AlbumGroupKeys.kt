package com.onegallery.sync.sync

import com.onegallery.sync.data.local.db.SyncMetadataEntity

/** Uma chave de álbum/pasta para índice, grade, capas e filtros — sempre a mesma regra. */
object AlbumGroupKeys {
    fun fromEntity(entity: SyncMetadataEntity): String {
        entity.userAlbumName?.takeIf { it.isNotBlank() }?.let { return it }
        return fromPaths(
            canonicalPath = entity.canonicalPath,
            localGalleryRelativePath = entity.localGalleryRelativePath,
            fallbackAlbumName = entity.albumName,
        )
    }

    fun fromPaths(
        canonicalPath: String,
        localGalleryRelativePath: String? = null,
        fallbackAlbumName: String = "",
    ): String {
        return SyncPaths.folderAlbumName(
            canonicalPath = canonicalPath,
            relativePath = localGalleryRelativePath,
            bucketDisplayName = fallbackAlbumName,
        )
    }
}
