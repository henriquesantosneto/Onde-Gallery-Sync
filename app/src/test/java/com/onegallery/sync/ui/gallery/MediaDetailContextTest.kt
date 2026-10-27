package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import org.junit.Assert.assertEquals
import org.junit.Test

class MediaDetailContextTest {

    private fun item(path: String, album: String) = GalleryDisplayItem(
        canonicalPath = path,
        displayName = "$path.jpg",
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
    fun staleAlbumFilterDoesNotReplaceThePhotoJustOpenedFromAnotherAlbum() {
        val previous = item("seen-in-album-a", "Album A")
        val opened = item("tapped-in-album-b", "Album B")
        val library = listOf(previous, item("other-a", "Album A"), opened)

        val paths = detailContextItems(
            allItems = library,
            albumFilter = "Album A",
            locationFilter = null,
            placeMatchKey = null,
            anchorPath = opened.canonicalPath,
            sortOrder = GallerySortOrder.NAME_A_Z,
        ).map { it.canonicalPath }

        assertEquals(listOf(opened.canonicalPath), paths)
    }

    @Test
    fun mainGridIgnoresAnAlbumFilterThatDoesNotContainTheTappedPhoto() {
        val albumPhoto = item("from-album", "Férias")
        val tapped = item("from-grid", "Camera")
        val library = listOf(albumPhoto, tapped)

        val paths = detailContextItems(
            allItems = library,
            albumFilter = "Férias",
            locationFilter = null,
            placeMatchKey = null,
            anchorPath = tapped.canonicalPath,
            sortOrder = GallerySortOrder.NAME_A_Z,
        ).map { it.canonicalPath }

        assertEquals(listOf(tapped.canonicalPath), paths)
    }

    @Test
    fun albumOnScreenWinsOverThePreviousAlbumFilter() {
        val previous = item("seen-in-album-a", "Album A")
        val opened = item("tapped-in-album-b", "Album B")
        val sibling = item("also-b", "Album B")
        val library = listOf(previous, opened, sibling)

        val paths = detailItemsForOpenedPath(
            allItems = library,
            openedPath = opened.canonicalPath,
            browsePaths = listOf(sibling.canonicalPath, opened.canonicalPath),
            albumFilter = "Album A",
            locationFilter = null,
            placeMatchKey = null,
            sortOrder = GallerySortOrder.NAME_A_Z,
        ).map { it.canonicalPath }

        assertEquals(listOf(sibling.canonicalPath, opened.canonicalPath), paths)
    }

    @Test
    fun photoInsideTheActiveAlbumStaysInThatAlbum() {
        val first = item("a1", "Album A")
        val opened = item("a2", "Album A")
        val other = item("b1", "Album B")

        val paths = detailContextItems(
            allItems = listOf(first, opened, other),
            albumFilter = "Album A",
            locationFilter = null,
            placeMatchKey = null,
            anchorPath = opened.canonicalPath,
            sortOrder = GallerySortOrder.NAME_A_Z,
        ).map { it.canonicalPath }

        assertEquals(listOf("a1", "a2"), paths)
    }
}
