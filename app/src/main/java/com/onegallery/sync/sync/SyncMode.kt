package com.onegallery.sync.sync

enum class SyncMode {
    LOCAL_ONLY,
    /** Pasta Pictures/OneGallerySync — o app OneDrive faz upload automático. */
    ONEDRIVE_LOCAL_MIRROR,
    /** SAF: usuário escolhe pasta na nuvem (quando o seletor mostra OneDrive). */
    ONEDRIVE_FOLDER,
    ONEDRIVE_GRAPH,
}
