package com.onegallery.sync.sync

import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.ui.gallery.GalleryMapper

internal object GalleryThumbnailLabels {
    fun operationLabel(row: SyncMetadataEntity): String {
        val hasLocalSource = row.localMediaId != null ||
            !row.localContentUri.isNullOrBlank() ||
            !row.cachedFilePath.isNullOrBlank()
        val isVideo = GalleryMapper.isVideoFileName(row.displayName)
        return when {
            isVideo && hasLocalSource && row.oneDriveItemId == null ->
                "Gerando miniatura do vídeo no aparelho…"
            isVideo && hasLocalSource ->
                "Miniatura do vídeo (aparelho ou nuvem)…"
            isVideo && row.oneDriveItemId != null ->
                "Miniatura do vídeo na nuvem…"
            row.oneDriveItemId != null && !hasLocalSource ->
                "Baixando preview do OneDrive (Graph)…"
            row.oneDriveItemId != null && hasLocalSource ->
                "Gravando preview no cache (nuvem ou aparelho)…"
            else -> "Gravando preview local no cache…"
        }
    }

    fun prefetchProgressDetailSuffix(
        localSourcesOnly: Boolean,
        cloudSourcesOnly: Boolean,
    ): String {
        return when {
            cloudSourcesOnly ->
                "Só baixa JPEG pequeno do OneDrive — não baixa foto/vídeo original."
            localSourcesOnly ->
                "Copia preview do MediaStore para o cache — grade já mostra o aparelho."
            else ->
                "Completa o que falta: Graph (nuvem) ou cache local — sem reenviar arquivos."
        }
    }
}
