package com.onegallery.sync.data.local

import com.onegallery.sync.TestApplication
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import com.onegallery.sync.ui.gallery.GalleryDisplayItem
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class PlaceRegionGroupingTest {

    @Test
    fun citiesInTheSameStateShareOneAlbum() {
        val campinas = photo("campinas.jpg", "Cambuí, Campinas, São Paulo, Brasil", -22.90, -47.06, 11L)
        val santos = photo("santos.jpg", "Gonzaga, Santos, São Paulo, Brasil", -23.96, -46.33, 12L)
        val rio = photo("rio.jpg", "Copacabana, Rio de Janeiro, Rio de Janeiro, Brasil", -22.97, -43.18, 13L)

        val groups = groupDisplayItemsForPlaceAlbums(listOf(campinas, santos, rio))

        assertEquals(2, groups.size)
        assertEquals(placeAlbumBucketEncodedKey(campinas), placeAlbumBucketEncodedKey(santos))
        assertNotEquals(placeAlbumBucketEncodedKey(campinas), placeAlbumBucketEncodedKey(rio))
        assertEquals(
            "Brasil · São Paulo",
            placeAlbumTitleForGroup(listOf(campinas, santos)),
        )
        assertEquals("Brasil · Rio de Janeiro", placeAlbumTitleForGroup(listOf(rio)))
        assertEquals(placeAlbumBucketEncodedKey(campinas), campinas.preferredPlaceBrowseEncodedKey())
    }

    @Test
    fun countryOnlyStaysOneAlbumAndGpsWithoutAddressKeepsACell() {
        val franceA = photo("a.jpg", "França", 48.85, 2.35, 21L)
        val franceB = photo("b.jpg", "França", 43.70, 7.26, 22L)
        val unlabeled = photo("c.jpg", "48.1° N", 48.1, 2.3, 23L)

        assertEquals(placeAlbumBucketEncodedKey(franceA), placeAlbumBucketEncodedKey(franceB))
        val franceKey = PlaceComponents.fromGeocoderLabel("França")!!.preferredBrowseKey("França")
        assertEquals(PlaceLevel.COUNTRY, franceKey.level)
        assertNotEquals(placeAlbumBucketEncodedKey(franceA), placeAlbumBucketEncodedKey(unlabeled))
    }

    @Test
    fun metadataUsesTheSameStateKey() {
        val entity = metadata("Centro, Curitiba, Paraná, Brasil", -25.42, -49.27)
        val key = placeAlbumBucketEncodedKey(entity)
        val expected = PlaceComponents.fromGeocoderLabel("Centro, Curitiba, Paraná, Brasil")!!
            .preferredBrowseKey(null)
            .encode()
        assertEquals(expected, key)
        assertEquals(PlaceLevel.ADMIN_AREA, PlaceMatchKey.decode(key!!)?.level)
    }

    private fun photo(
        path: String,
        label: String,
        lat: Double,
        lon: Double,
        mediaId: Long,
    ) = GalleryDisplayItem(
        canonicalPath = path,
        displayName = path,
        albumName = "Camera",
        kind = MediaKind.IMAGE,
        cloudState = CloudSyncState.SYNCED,
        localMediaId = mediaId,
        oneDriveItemId = null,
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        localUri = null,
        cachedOriginal = null,
        sortTimestamp = 1_700_000_000L,
        locationLabel = label,
        latitude = lat,
        longitude = lon,
    )

    private fun metadata(label: String, lat: Double, lon: Double) = SyncMetadataEntity(
        canonicalPath = "curitiba.jpg",
        displayName = "curitiba.jpg",
        albumName = "Camera",
        localMediaId = 2L,
        localContentUri = null,
        oneDriveItemId = null,
        localModifiedAt = null,
        remoteModifiedAt = null,
        cloudState = CloudSyncState.SYNCED,
        cachedFilePath = null,
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        lastError = null,
        locationLabel = label,
        latitude = lat,
        longitude = lon,
    )
}
