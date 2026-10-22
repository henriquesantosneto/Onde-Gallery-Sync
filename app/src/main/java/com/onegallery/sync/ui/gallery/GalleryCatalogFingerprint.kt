package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.db.SyncMetadataEntity

/** Assinatura estável do índice Room (ordem das linhas no SELECT não é garantida). */
data class GalleryCatalogFingerprint(
    val rowCount: Int,
    /** XOR dos paths + estado — **não** usa timestamps (sync parado ainda atualiza mod times). */
    val pathChecksum: Long,
    /** Legado/gravação em spill; não entra em [matchesIndex]. */
    val maxModifiedSeconds: Long = 0L,
) {
    /** Igualdade de índice para cache/spill — ignorar maxModified e ordem SQLite. */
    fun matchesIndex(other: GalleryCatalogFingerprint): Boolean =
        rowCount == other.rowCount && pathChecksum == other.pathChecksum

    companion object {
        fun fromDisplayPaths(paths: List<String>): GalleryCatalogFingerprint {
            if (paths.isEmpty()) return GalleryCatalogFingerprint(0, 0L, 0L)
            var checksum = 0L
            for (path in paths) {
                checksum = checksum xor path.hashCode().toLong()
            }
            return GalleryCatalogFingerprint(
                rowCount = paths.size,
                pathChecksum = checksum,
                maxModifiedSeconds = 0L,
            )
        }

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
                pathChecksum = checksum,
                maxModifiedSeconds = maxMod,
            )
        }

        /** Spill v1/v2 (head/tail) — compatibilidade; na dúvida remonta uma vez. */
        fun legacyFromSpill(
            rowCount: Int,
            headPath: String?,
            tailPath: String?,
            maxModifiedSeconds: Long,
        ): GalleryCatalogFingerprint =
            GalleryCatalogFingerprint(
                rowCount = rowCount,
                pathChecksum = (headPath?.hashCode()?.toLong() ?: 0L) xor
                    (tailPath?.hashCode()?.toLong() ?: 0L),
                maxModifiedSeconds = maxModifiedSeconds,
            )
    }
}
