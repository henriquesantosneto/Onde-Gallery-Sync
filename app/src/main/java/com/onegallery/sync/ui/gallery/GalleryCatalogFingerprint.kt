package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.db.SyncMetadataEntity

/** Assinatura barata do índice Room — evita remontar catálogo idêntico a cada emissão. */
data class GalleryCatalogFingerprint(
    val rowCount: Int,
    val headPath: String?,
    val tailPath: String?,
    val maxModifiedSeconds: Long,
) {
    companion object {
        fun from(metadata: List<SyncMetadataEntity>): GalleryCatalogFingerprint {
            if (metadata.isEmpty()) {
                return GalleryCatalogFingerprint(0, null, null, 0L)
            }
            var maxMod = 0L
            for (row in metadata) {
                val mod = row.localModifiedAt ?: row.remoteModifiedAt ?: 0L
                if (mod > maxMod) maxMod = mod
            }
            return GalleryCatalogFingerprint(
                rowCount = metadata.size,
                headPath = metadata.first().canonicalPath,
                tailPath = metadata.last().canonicalPath,
                maxModifiedSeconds = maxMod,
            )
        }
    }
}
