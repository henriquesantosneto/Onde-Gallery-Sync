package com.onegallery.sync.ui.gallery

/**
 * Modelo Coil para miniatura Graph de item só na nuvem (principalmente vídeo).
 * Evita depender só do JPEG em disco antes de pintar a célula.
 */
data class OneDriveCloudThumbnailKey(
    val canonicalPath: String,
    val oneDriveItemId: String,
)
