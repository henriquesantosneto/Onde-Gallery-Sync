package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.db.SyncMetadataEntity

/** Assinatura estável do índice Room (ordem das linhas no SELECT não é garantida). */
data class GalleryCatalogFingerprint(
    val rowCount: Int,
    val maxModifiedSeconds: Long,
    /** XOR dos paths — detecta add/remove sem depender de first/last aleatório. */
    val pathChecksum: Long,
) {
    companion object {
        fun from(metadata: List<SyncMetadataEntity>): GalleryCatalogFingerprint {
            if (metadata.isEmpty()) {
                return GalleryCatalogFingerprint(0, 0L, 0L)
            }
            var maxMod = 0L
            var checksum = 0L
            for (row in metadata) {
                val mod = row.localModifiedAt ?: row.remoteModifiedAt ?: 0L
                if (mod > maxMod) maxMod = mod
                checksum = checksum xor row.canonicalPath.hashCode().toLong()
                checksum = checksum * 31L + row.cloudState.ordinal
            }
            return GalleryCatalogFingerprint(
                rowCount = metadata.size,
                maxModifiedSeconds = maxMod,
                pathChecksum = checksum,
            )
        }

        /** Spill v1/v2 (head/tail) — só para tentar casar arquivos antigos uma vez. */
        fun legacyFromSpill(
            rowCount: Int,
            headPath: String?,
            tailPath: String?,
            maxModifiedSeconds: Long,
        ): GalleryCatalogFingerprint =
            GalleryCatalogFingerprint(
                rowCount = rowCount,
                maxModifiedSeconds = maxModifiedSeconds,
                pathChecksum = (headPath?.hashCode()?.toLong() ?: 0L) xor
                    (tailPath?.hashCode()?.toLong() ?: 0L),
            )
    }
}
