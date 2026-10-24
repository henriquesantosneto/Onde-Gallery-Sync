package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryAlbumListItemsTest {

    private fun cloudVideo(path: String) = GalleryDisplayItem(
        canonicalPath = path,
        displayName = "clip.mp4",
        albumName = "Camera",
        kind = MediaKind.VIDEO,
        cloudState = CloudSyncState.CLOUD_ONLY,
        localMediaId = null,
        oneDriveItemId = "remote-1",
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        localUri = null,
        cachedOriginal = null,
        sortTimestamp = 1L,
        showInBrowseGrid = false,
    )

    @Test
    fun albumGrouping_prefersVisibleItems() {
        val catalog = listOf(cloudVideo("a"), cloudVideo("b"))
        val visible = listOf(cloudVideo("a"))
        assertEquals(visible, itemsForAlbumListGrouping(catalog, visible))
    }

    @Test
    fun albumGrouping_withThumbnailOnly_doesNotFallBackToFullCatalog() {
        val catalog = listOf(cloudVideo("a"), cloudVideo("b"))
        val grouped = itemsForAlbumListGrouping(catalog, visibleItems = emptyList(), onlyItemsWithThumbnail = true)
        assertTrue(grouped.isEmpty())
    }

    @Test
    fun albumGrouping_withoutThumbnailFilter_fallsBackToCatalog() {
        val catalog = listOf(cloudVideo("a"))
        assertEquals(catalog, itemsForAlbumListGrouping(catalog, visibleItems = emptyList(), onlyItemsWithThumbnail = false))
    }
}
