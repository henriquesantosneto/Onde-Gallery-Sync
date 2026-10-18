package com.onegallery.sync.ui.places

import com.onegallery.sync.ui.gallery.GalleryDisplayItem

/** Um pin no mapa por foto/vídeo, na coordenada EXIF exata. */
data class PhotoMapPin(
    val item: GalleryDisplayItem,
    val latitude: Double,
    val longitude: Double,
)

fun buildPhotoMapPins(items: List<GalleryDisplayItem>): List<PhotoMapPin> {
    return items.mapNotNull { item ->
        val lat = item.latitude ?: return@mapNotNull null
        val lon = item.longitude ?: return@mapNotNull null
        if (!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
        PhotoMapPin(item = item, latitude = lat, longitude = lon)
    }
}
