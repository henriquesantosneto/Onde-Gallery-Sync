package com.onegallery.sync.data.local

import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.ui.gallery.GalleryDisplayItem

/** Agrupa itens colapsados em álbuns de local (célula ~[PLACE_CLUSTER_RADIUS_KM] km ou rótulo legado). */
internal fun groupDisplayItemsForPlaceAlbums(
    items: List<GalleryDisplayItem>,
): Map<String, List<GalleryDisplayItem>> {
    val collapsed = collapseItemsForPlaceAlbums(items)
    val groups = linkedMapOf<String, MutableList<GalleryDisplayItem>>()
    for (item in collapsed) {
        val key = placeAlbumBucketEncodedKey(item) ?: continue
        groups.getOrPut(key) { mutableListOf() }.add(item)
    }
    return groups
}

internal fun groupMetadataForPlaceAlbums(
    metadata: List<SyncMetadataEntity>,
): Map<String, List<SyncMetadataEntity>> {
    val collapsed = collapseMetadataForPlaceAlbums(metadata)
    val groups = linkedMapOf<String, MutableList<SyncMetadataEntity>>()
    for (entity in collapsed) {
        val key = placeAlbumBucketEncodedKey(entity) ?: continue
        groups.getOrPut(key) { mutableListOf() }.add(entity)
    }
    return groups
}

/**
 * Título do álbum: endereço mais completo do grupo; a capa entra na disputa se tiver rótulo
 * (fica o mais longo / específico entre capa e demais fotos da célula).
 */
internal fun placeAlbumTitleForGroup(
    group: List<GalleryDisplayItem>,
    coverItem: GalleryDisplayItem? = null,
): String {
    if (group.isEmpty()) return "Local"
    val richestInGroup = group.reduce(::pickBetterForPlaceGrouping)
    val source = coverItem?.let { pickBetterForPlaceGrouping(it, richestInGroup) } ?: richestInGroup
    return placeAlbumTitleFromItem(source)
}

internal fun placeAlbumTitleForMetadataGroup(
    group: List<SyncMetadataEntity>,
): String {
    if (group.isEmpty()) return "Local"
    val source = group.reduce(::pickBetterForPlaceGroupingEntity)
    val label = source.locationLabel?.trim().orEmpty()
    val components = placeComponentsForEntity(source)
        ?: label.takeIf { it.isNotEmpty() }?.let { PlaceComponents.fromGeocoderLabel(it) }
    return components?.hierarchicalDisplayTitle()?.takeIf { it.isNotBlank() }
        ?: label.ifBlank { "Local" }
}

internal fun placeAlbumBucketEncodedKey(item: GalleryDisplayItem): String? {
    val coords = validPlaceCoordinates(item.latitude, item.longitude)
    if (coords != null) {
        val cell = geographicCellKey(coords.first, coords.second)
        return PlaceMatchKey.geoCell(cell).encode()
    }
    return textPlaceAlbumEncodedKey(item.locationLabel)
}

internal fun placeAlbumBucketEncodedKey(entity: SyncMetadataEntity): String? {
    val coords = validPlaceCoordinates(entity.latitude, entity.longitude)
    if (coords != null) {
        val cell = geographicCellKey(coords.first, coords.second)
        return PlaceMatchKey.geoCell(cell).encode()
    }
    return textPlaceAlbumEncodedKey(entity.locationLabel)
}

private fun textPlaceAlbumEncodedKey(locationLabel: String?): String? {
    val label = locationLabel?.trim().orEmpty()
    if (label.isEmpty()) return null
    if (label.contains('°')) return PlaceMatchKey.legacyLabel(label).encode()
    val title = PlaceComponents.fromGeocoderLabel(label)?.hierarchicalDisplayTitle()?.takeIf { it.isNotBlank() }
        ?: label
    return PlaceMatchKey.legacyLabel(title).encode()
}

internal fun GalleryDisplayItem.placeAlbumDisplayTitle(): String = placeAlbumTitleFromItem(this)

private fun placeAlbumTitleFromItem(item: GalleryDisplayItem): String {
    val label = item.locationLabel?.trim().orEmpty()
    val components = PlaceComponents.forItem(item)
        ?: label.takeIf { it.isNotEmpty() }?.let { PlaceComponents.fromGeocoderLabel(it) }
    return components?.hierarchicalDisplayTitle()?.takeIf { it.isNotBlank() }
        ?: label.ifBlank { "Local" }
}

private fun placeComponentsForEntity(entity: SyncMetadataEntity): PlaceComponents? {
    val label = entity.locationLabel?.trim().orEmpty()
    if (label.isEmpty()) return null
    return PlaceComponents.fromGeocoderLabel(label)
}

/** Chave de navegação ao abrir “este local” a partir de uma foto com GPS. */
fun GalleryDisplayItem.preferredPlaceBrowseEncodedKey(): String? {
    val coords = validPlaceCoordinates(latitude, longitude) ?: return null
    val cell = geographicCellKey(coords.first, coords.second)
    return PlaceMatchKey.geoCell(cell).encode()
}
