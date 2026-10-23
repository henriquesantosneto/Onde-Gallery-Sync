package com.onegallery.sync.data.local

import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.ui.gallery.GalleryDisplayItem
import com.onegallery.sync.ui.gallery.galleryDedupeKey

/** Pontuação do endereço resolvido — preferir bairro/cidade completos ao agrupar duplicatas. */
internal fun PlaceComponents.locationSpecificityScore(): Int {
    var score = 0
    if (!country.isNullOrBlank()) score += 1
    if (!adminArea.isNullOrBlank()) score += 1
    if (!locality.isNullOrBlank()) score += 2
    if (!subLocality.isNullOrBlank()) score += 4
    return score
}

internal fun GalleryDisplayItem.locationSpecificityScore(): Int {
    val fromComponents = PlaceComponents.forItem(this)?.locationSpecificityScore()
    if (fromComponents != null && fromComponents > 0) return fromComponents
    val parts = locationLabel?.trim()?.split(", ")?.count { it.isNotBlank() } ?: 0
    return parts
}

internal fun SyncMetadataEntity.locationSpecificityScore(): Int {
    val label = locationLabel?.trim().orEmpty()
    if (label.isEmpty()) return 0
    return PlaceComponents.fromGeocoderLabel(label)?.locationSpecificityScore()
        ?: label.split(", ").count { it.isNotBlank() }
}

/**
 * Mesma captura (ex.: cópia WhatsApp + linha indexada): coordenadas arredondadas + segundo da foto.
 */
internal fun GalleryDisplayItem.placeGeoDedupeKey(): String? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    val epochSec = sortTimestampToPlaceEpochSeconds(sortTimestamp) ?: return null
    val latKey = (lat * 1_000).toLong()
    val lonKey = (lon * 1_000).toLong()
    return "geo:$latKey:$lonKey:$epochSec"
}

internal fun SyncMetadataEntity.placeGeoDedupeKey(): String? {
    val lat = latitude ?: return null
    val lon = longitude ?: return null
    if (lat !in -90.0..90.0 || lon !in -180.0..180.0) return null
    val epochSec = capturedAtSeconds?.takeIf { it > 0L }
        ?: remoteModifiedAt?.takeIf { it > 0L }
        ?: localModifiedAt?.takeIf { it > 0L }
        ?: return null
    val latKey = (lat * 1_000).toLong()
    val lonKey = (lon * 1_000).toLong()
    return "geo:$latKey:$lonKey:$epochSec"
}

private fun sortTimestampToPlaceEpochSeconds(sortTimestamp: Long): Long? {
    if (sortTimestamp <= 0L) return null
    return if (sortTimestamp > 1_000_000_000_000L) sortTimestamp / 1000L else sortTimestamp
}

/** Mantém a linha com endereço mais completo quando várias mídias são a mesma captura. */
internal fun pickBetterForPlaceGrouping(
    candidate: GalleryDisplayItem,
    incumbent: GalleryDisplayItem,
): GalleryDisplayItem {
    val cScore = candidate.locationSpecificityScore()
    val iScore = incumbent.locationSpecificityScore()
    if (cScore != iScore) return if (cScore > iScore) candidate else incumbent
    val cLabel = candidate.locationLabel?.length ?: 0
    val iLabel = incumbent.locationLabel?.length ?: 0
    if (cLabel != iLabel) return if (cLabel > iLabel) candidate else incumbent
    return if (candidate.sortTimestamp >= incumbent.sortTimestamp) candidate else incumbent
}

internal fun pickBetterForPlaceGroupingEntity(
    candidate: SyncMetadataEntity,
    incumbent: SyncMetadataEntity,
): SyncMetadataEntity {
    val cScore = candidate.locationSpecificityScore()
    val iScore = incumbent.locationSpecificityScore()
    if (cScore != iScore) return if (cScore > iScore) candidate else incumbent
    val cLabel = candidate.locationLabel?.length ?: 0
    val iLabel = incumbent.locationLabel?.length ?: 0
    if (cLabel != iLabel) return if (cLabel > iLabel) candidate else incumbent
    return if (candidate.sortTimestamp() >= incumbent.sortTimestamp()) candidate else incumbent
}

private fun SyncMetadataEntity.sortTimestamp(): Long =
    capturedAtSeconds?.takeIf { it > 0L }
        ?: remoteModifiedAt?.takeIf { it > 0L }
        ?: localModifiedAt?.takeIf { it > 0L }
        ?: 0L

internal fun SyncMetadataEntity.placeMediaDedupeKey(): String = placeAlbumCollapseKeyForEntity()

internal fun SyncMetadataEntity.placeAlbumCollapseKeyForEntity(): String {
    localMediaId?.let { return "localId:$it" }
    oneDriveItemId?.let { return "cloudId:$it" }
    placeGeoDedupeKey()?.let { return "geoCap:$it" }
    val path = canonicalPath.trim()
    if (path.isNotEmpty()) return "path:$path"
    localContentUri?.trim()?.takeIf { it.isNotEmpty() }?.let { return "uri:$it" }
    return "path:$canonicalPath"
}

private fun <T> collapsePlaceCandidatesByKey(
    items: List<T>,
    keyFor: (T) -> String,
    pickBetter: (T, T) -> T,
): List<T> {
    if (items.size <= 1) return items
    val best = LinkedHashMap<String, T>(items.size)
    for (item in items) {
        val key = keyFor(item)
        val existing = best[key]
        best[key] = if (existing == null) item else pickBetter(item, existing)
    }
    return best.values.toList()
}

/**
 * Uma foto por mídia e por captura geográfica antes de montar álbuns de local.
 * Sem colapso por [galleryDedupeKey], a mesma câmera pode gerar duas linhas (GPS parcial vs
 * endereço completo) e aparecer em álbum de cidade e de bairro ao mesmo tempo.
 */
internal fun GalleryDisplayItem.placeAlbumCollapseKey(): String {
    localMediaId?.let { return "localId:$it" }
    oneDriveItemId?.let { return "cloudId:$it" }
    placeGeoDedupeKey()?.let { return "geoCap:$it" }
    val path = canonicalPath.trim()
    if (path.isNotEmpty()) return "path:$path"
    return galleryDedupeKey()
}

internal fun collapseItemsForPlaceAlbums(items: List<GalleryDisplayItem>): List<GalleryDisplayItem> {
    if (items.size <= 1) return items
    val afterMedia = collapsePlaceCandidatesByKey(items, { it.placeAlbumCollapseKey() }, ::pickBetterForPlaceGrouping)
    return collapsePlaceCandidatesByKey(
        afterMedia,
        { it.placeGeoDedupeKey() ?: "path:${it.canonicalPath}" },
        ::pickBetterForPlaceGrouping,
    )
}

internal fun collapseMetadataForPlaceAlbums(metadata: List<SyncMetadataEntity>): List<SyncMetadataEntity> {
    if (metadata.size <= 1) return metadata
    val afterMedia = collapsePlaceCandidatesByKey(
        metadata,
        { it.placeMediaDedupeKey() },
        ::pickBetterForPlaceGroupingEntity,
    )
    return collapsePlaceCandidatesByKey(
        afterMedia,
        { it.placeGeoDedupeKey() ?: "path:${it.canonicalPath}" },
        ::pickBetterForPlaceGroupingEntity,
    )
}
