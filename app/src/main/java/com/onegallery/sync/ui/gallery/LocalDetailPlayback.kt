package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaKind

/** Trecho de vídeo local antes de seguir para o próximo que está no aparelho. */
internal const val LOCAL_VIDEO_PREVIEW_MS = 5_000L

/**
 * Vídeo com arquivo no aparelho. Dá play na passagem.
 * Item só na nuvem não entra: a tela fica na miniatura.
 */
internal fun GalleryDisplayItem.playsInlineLocalVideo(): Boolean {
    if (kind != MediaKind.VIDEO) return false
    return playbackUri() != null
}

/** Foto ou vídeo que a tela abre pelo arquivo do aparelho, sem JPEG gerado. */
internal fun GalleryDisplayItem.showsFromLocalFile(): Boolean {
    if (playsInlineLocalVideo()) return true
    if (kind != MediaKind.IMAGE) return false
    return playbackUri() != null
}

/** Próximo vídeo local depois de [fromIndex]. Foto e nuvem ficam de fora da sequência. */
internal fun nextLocalDeviceVideoIndex(
    items: List<GalleryDisplayItem>,
    fromIndex: Int,
): Int? {
    if (fromIndex < 0) return null
    for (index in (fromIndex + 1)..items.lastIndex) {
        if (items[index].playsInlineLocalVideo()) return index
    }
    return null
}
