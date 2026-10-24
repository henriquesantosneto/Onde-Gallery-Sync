package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.data.local.LocalMediaRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.data.local.db.SyncMetadataEntity

/** Mesma ordem que [com.onegallery.sync.sync.GalleryThumbnailService.localContentUri]. */
object GalleryLocalContentUri {
    fun resolve(
        entity: SyncMetadataEntity,
        kind: MediaKind,
        localMediaRepository: LocalMediaRepository?,
    ): Uri? {
        entity.localContentUri?.takeIf { it.isNotBlank() }?.let { parsed ->
            return Uri.parse(parsed)
        }
        val id = entity.localMediaId ?: return null
        return localMediaRepository?.contentUriForMediaId(id, kind)
    }
}
