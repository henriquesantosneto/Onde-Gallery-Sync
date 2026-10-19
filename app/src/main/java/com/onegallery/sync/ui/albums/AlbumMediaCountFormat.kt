package com.onegallery.sync.ui.albums

import com.onegallery.sync.data.local.MediaAlbum

/** Texto estilo Samsung Galeria (fotos / vídeos). */
fun formatAlbumMediaCount(album: MediaAlbum): String {
    return formatMediaCount(photos = album.photoCount, videos = album.videoCount)
}

fun formatMediaCount(photos: Int, videos: Int): String {
    val total = photos + videos
    if (total <= 0) return "Vazio"
    val parts = mutableListOf<String>()
    if (photos > 0) {
        parts.add(if (photos == 1) "1 foto" else "$photos fotos")
    }
    if (videos > 0) {
        parts.add(if (videos == 1) "1 vídeo" else "$videos vídeos")
    }
    return parts.joinToString(" · ")
}

/** Número grande no canto da capa (estilo Samsung). */
fun formatAlbumMediaCountBadge(album: MediaAlbum): String {
    val total = album.photoCount + album.videoCount
    return if (total > 0) total.toString() else "0"
}
