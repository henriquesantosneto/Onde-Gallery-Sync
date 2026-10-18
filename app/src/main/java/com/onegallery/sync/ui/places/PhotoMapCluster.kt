package com.onegallery.sync.ui.places

import com.onegallery.sync.ui.gallery.GalleryDisplayItem
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt

/** Raio aproximado de cada pin no mapa (região, não GPS exato por foto). */
const val MAP_PIN_CLUSTER_RADIUS_KM = 3.0

data class PhotoMapCluster(
    val id: String,
    val latitude: Double,
    val longitude: Double,
    val title: String,
    val items: List<GalleryDisplayItem>,
    val radiusKm: Double = MAP_PIN_CLUSTER_RADIUS_KM,
)

/**
 * Agrupa fotos por célula geográfica (~[MAP_PIN_CLUSTER_RADIUS_KM] km).
 * O pin fica no centro médio da célula; GPS exato fica nos detalhes de cada foto.
 */
fun buildPhotoMapClusters(
    items: List<GalleryDisplayItem>,
    clusterRadiusKm: Double = MAP_PIN_CLUSTER_RADIUS_KM,
): List<PhotoMapCluster> {
    val withCoords = items.mapNotNull { item ->
        val lat = item.latitude ?: return@mapNotNull null
        val lon = item.longitude ?: return@mapNotNull null
        if (!lat.isFinite() || !lon.isFinite()) return@mapNotNull null
        if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return@mapNotNull null
        Triple(item, lat, lon)
    }
    return withCoords
        .groupBy { (_, lat, lon) -> geographicCellKey(lat, lon, clusterRadiusKm) }
        .map { (cellId, group) ->
            val clusterItems = group.map { it.first }.sortedByDescending { it.sortTimestamp }
            val lat = group.map { it.second }.average()
            val lon = group.map { it.third }.average()
            val title = clusterItems.firstNotNullOfOrNull { item ->
                item.locationLabel?.takeIf { it.isNotBlank() }
            } ?: approximateRegionLabel(lat, lon)
            PhotoMapCluster(
                id = cellId,
                latitude = lat,
                longitude = lon,
                title = title,
                items = clusterItems,
                radiusKm = clusterRadiusKm,
            )
        }
        .sortedByDescending { it.items.size }
}

private fun geographicCellKey(lat: Double, lon: Double, radiusKm: Double): String {
    val latStep = radiusKm / 111.0
    val lonStep = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
    val latCell = floor(lat / latStep).toInt()
    val lonCell = floor(lon / lonStep).toInt()
    return "$latCell:$lonCell"
}

private fun approximateRegionLabel(lat: Double, lon: Double): String {
    val latStr = ((lat * 100.0).roundToInt() / 100.0).toString()
    val lonStr = ((lon * 100.0).roundToInt() / 100.0).toString()
    return "$latStr°, $lonStr° (aprox.)"
}
