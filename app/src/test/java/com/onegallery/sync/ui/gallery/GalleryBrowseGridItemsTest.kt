package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryBrowseGridItemsTest {

    private fun item(path: String, album: String) = GalleryDisplayItem(
        canonicalPath = path,
        displayName = "f.jpg",
        albumName = album,
        kind = MediaKind.IMAGE,
        cloudState = CloudSyncState.SYNCED,
        localMediaId = 1L,
        oneDriveItemId = null,
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        localUri = null,
        cachedOriginal = null,
        sortTimestamp = 1L,
    )

    @Test
    fun albumRoute_ignoresStaleFullLibraryInState() {
        val library = listOf(
            item("a", "Camera"),
            item("b", "DMSO"),
            item("c", "Camera"),
        )
        val staleState = library
        val result = resolveBrowseGridItems(
            requiresActiveBrowseFilter = true,
            libraryItems = library,
            browseItemsFromState = staleState,
            sortOrder = GallerySortOrder.DATE_NEWEST,
            routeAlbumFolderKey = "DMSO",
            routePlaceMatchKey = null,
            routePlaceTitle = null,
        )
        assertEquals(1, result.size)
        assertEquals("DMSO", result.single().albumName)
    }

    @Test
    fun albumRoute_usesStateWhenAlreadyFiltered() {
        val dmsoOnly = listOf(item("b", "DMSO"))
        val library = listOf(item("a", "Camera"), item("b", "DMSO"))
        val result = resolveBrowseGridItems(
            requiresActiveBrowseFilter = true,
            libraryItems = library,
            browseItemsFromState = dmsoOnly,
            sortOrder = GallerySortOrder.DATE_NEWEST,
            routeAlbumFolderKey = "DMSO",
            routePlaceMatchKey = null,
            routePlaceTitle = null,
        )
        assertTrue(browseGridItemsAlbumFolderMatches(result, "DMSO"))
    }
}
