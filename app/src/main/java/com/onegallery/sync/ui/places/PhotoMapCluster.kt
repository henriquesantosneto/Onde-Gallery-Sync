package com.onegallery.sync.ui.places

import com.onegallery.sync.ui.gallery.GalleryDisplayItem
import kotlin.math.roundToInt

data class PhotoMapCluster(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val items: List<GalleryDisplayItem>,
)

fun buildPhotoMapClusters(items: List<GalleryDisplayItem>): List<PhotoMapCluster> {
    return items
        .filter { it.latitude != null && it.longitude != null }
        .groupBy { item ->
            item.locationLabel?.takeIf { it.isNotBlank() }
                ?: "${roundCoord(item.latitude!!)}|${roundCoord(item.longitude!!)}"
        }
        .map { (key, group) ->
            val lat = group.mapNotNull { it.latitude }.average()
            val lon = group.mapNotNull { it.longitude }.average()
            val title = group.firstNotNullOfOrNull { it.locationLabel?.takeIf { l -> l.isNotBlank() } }
                ?: "${roundCoord(lat)}°, ${roundCoord(lon)}°"
            PhotoMapCluster(
                id = key,
                latitude = lat,
                longitude = lon,
                title = title,
                items = group.sortedByDescending { it.sortTimestamp },
            )
        }
        .sortedByDescending { it.items.size }
}

private fun roundCoord(value: Double): String {
    return ((value * 1000.0).roundToInt() / 1000.0).toString()
}
