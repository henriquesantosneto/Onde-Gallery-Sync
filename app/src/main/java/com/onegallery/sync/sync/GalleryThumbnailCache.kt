package com.onegallery.sync.sync

import java.io.File

/** API de cache de miniatura para grade, detalhe e mapa — sem acoplar ao sync completo. */
interface GalleryThumbnailCache {
    suspend fun ensureThumbnailCached(
        canonicalPath: String,
        onProgress: ((bytesCompleted: Long, totalBytes: Long?) -> Unit)? = null,
        preferCloudFirst: Boolean = false,
        lightweightPrefetch: Boolean = false,
        localSourcesOnly: Boolean = false,
        cloudSourcesOnly: Boolean = false,
        cloudVideoAggressive: Boolean = false,
        /** Fila de sync: não abre o decodificador nativo de vídeo, que fecha o processo sem erro. */
        decodeVideoFrames: Boolean = true,
    ): Result<File?>
}
