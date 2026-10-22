package com.onegallery.sync.data.local

import kotlin.math.cos
import kotlin.math.floor

/** Raio (~3 km) para agrupar álbuns de local — mesmo valor usado no mapa historicamente. */
const val PLACE_CLUSTER_RADIUS_KM = 3.0

/** Célula geográfica estável para agrupar fotos numa região aproximada de [radiusKm]. */
fun geographicCellKey(lat: Double, lon: Double, radiusKm: Double = PLACE_CLUSTER_RADIUS_KM): String {
    val latStep = radiusKm / 111.0
    val lonStep = radiusKm / (111.0 * cos(Math.toRadians(lat)).coerceAtLeast(0.05))
    val latCell = floor(lat / latStep).toInt()
    val lonCell = floor(lon / lonStep).toInt()
    return "$latCell:$lonCell"
}

internal fun validPlaceCoordinates(latitude: Double?, longitude: Double?): Pair<Double, Double>? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    if (!lat.isFinite() || !lon.isFinite()) return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    return lat to lon
}
