package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.PlaceComponents
import com.onegallery.sync.data.local.PlaceMatchKey
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.SyncPaths

/** Chaves de capa na grade (`folder:…` / `place:…`) afetadas por um item do índice. */
object AlbumCoverBucketKeys {
    fun forEntity(entity: SyncMetadataEntity): Set<String> {
        val keys = linkedSetOf<String>()
        keys.add("album:${albumGroupKey(entity)}")
        appendPlaceKeys(keys, entity.locationLabel)
        return keys
    }

    fun forDisplayItem(item: GalleryDisplayItem): Set<String> {
        val keys = linkedSetOf<String>()
        keys.add("album:${item.albumName}")
        appendPlaceKeys(keys, item.locationLabel)
        return keys
    }

    fun albumGroupKey(entity: SyncMetadataEntity): String {
        entity.userAlbumName?.takeIf { it.isNotBlank() }?.let { return it }
        val fromCanonical = SyncPaths.albumFromCanonical(entity.canonicalPath)
        if (fromCanonical != "Raiz") return fromCanonical
        return entity.albumName.ifBlank { "Raiz" }
    }

    private fun appendPlaceKeys(keys: MutableSet<String>, locationLabel: String?) {
        val label = locationLabel?.trim().orEmpty()
        val components = label.takeIf { it.isNotEmpty() }?.let { PlaceComponents.fromGeocoderLabel(it) }
        when {
            components != null -> {
                keys.add("place:${PlaceMatchKey.forComponents(components, label).encode()}")
            }
            label.isNotEmpty() -> {
                keys.add("place:${PlaceMatchKey.legacyLabel(label).encode()}")
            }
        }
    }
}
