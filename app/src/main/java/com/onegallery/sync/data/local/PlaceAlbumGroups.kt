package com.onegallery.sync.data.local

import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.ui.gallery.GalleryDisplayItem

/** Mesmo critério da lista Locais: um item só entra no álbum da sua chave de bucket. */
fun GalleryDisplayItem.matchesPlaceAlbumBucket(encodedKey: String): Boolean {
    if (encodedKey.isBlank()) return false
    return placeAlbumBucketEncodedKey(this) == encodedKey
}

/** Agrupa em álbuns de país e estado. Célula de GPS só entra quando ainda não há endereço. */
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
 * Título do álbum: país e estado. A capa só entra na disputa do rótulo; cidade e bairro
 * não mudam o nome.
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
    return components?.regionDisplayTitle()?.takeIf { it.isNotBlank() }
        ?: label.ifBlank { "Local" }
}

internal fun placeAlbumBucketEncodedKey(item: GalleryDisplayItem): String? {
    regionKeyFromLabel(item.locationLabel)?.let { return it }
    val coords = validPlaceCoordinates(item.latitude, item.longitude)
    if (coords != null) {
        val cell = geographicCellKey(coords.first, coords.second)
        return PlaceMatchKey.geoCell(cell).encode()
    }
    return textPlaceAlbumEncodedKey(item.locationLabel)
}

internal fun placeAlbumBucketEncodedKey(entity: SyncMetadataEntity): String? {
    regionKeyFromLabel(entity.locationLabel)?.let { return it }
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
    regionKeyFromLabel(label)?.let { return it }
    return PlaceMatchKey.legacyLabel(label).encode()
}

private fun regionKeyFromLabel(locationLabel: String?): String? {
    val label = locationLabel?.trim().orEmpty()
    if (label.isEmpty() || label.contains('°')) return null
    val components = PlaceComponents.fromGeocoderLabel(label) ?: return null
    if (components.country.isNullOrBlank()) return null
    return components.preferredBrowseKey(label).encode()
}

internal fun GalleryDisplayItem.placeAlbumDisplayTitle(): String = placeAlbumTitleFromItem(this)

private fun placeAlbumTitleFromItem(item: GalleryDisplayItem): String {
    val label = item.locationLabel?.trim().orEmpty()
    val components = PlaceComponents.forItem(item)
        ?: label.takeIf { it.isNotEmpty() }?.let { PlaceComponents.fromGeocoderLabel(it) }
    return components?.regionDisplayTitle()?.takeIf { it.isNotBlank() }
        ?: label.ifBlank { "Local" }
}

private fun placeComponentsForEntity(entity: SyncMetadataEntity): PlaceComponents? {
    val label = entity.locationLabel?.trim().orEmpty()
    if (label.isEmpty()) return null
    return PlaceComponents.fromGeocoderLabel(label)
}

/** Chave de navegação ao abrir o álbum de país/estado desta foto. */
fun GalleryDisplayItem.preferredPlaceBrowseEncodedKey(): String? = placeAlbumBucketEncodedKey(this)
