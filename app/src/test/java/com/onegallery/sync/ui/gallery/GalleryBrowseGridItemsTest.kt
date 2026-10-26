package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.TestApplication
import java.io.File
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class GalleryBrowseGridItemsTest {

    private lateinit var repo: MediaCacheRepository

    @Before
    fun setUp() {
        repo = MediaCacheRepository(org.robolectric.RuntimeEnvironment.getApplication())
    }

    private fun item(path: String, album: String) = GalleryDisplayItem(
        canonicalPath = path,
        displayName = "f.jpg",
        albumName = album,
        kind = MediaKind.IMAGE,
        cloudState = CloudSyncState.SYNCED,
        localMediaId = 1L,
        oneDriveItemId = null,
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        localUri = Uri.parse("content://media/external/images/1"),
        cachedOriginal = null,
        cachedPlaceholderThumb = galleryTestJpeg(),
        sortTimestamp = 1L,
    )

    @Test
    fun photosTab_showsWholeLibraryEvenIfAlbumFilterIsStillInState() {
        val library = listOf(
            item("a", "Camera"),
            item("b", "DMSO"),
            item("c", "WhatsApp"),
        )
        val albumOnly = listOf(item("b", "DMSO"))
        val result = resolveBrowseGridItems(
            requiresActiveBrowseFilter = false,
            libraryItems = library,
            browseItemsFromState = albumOnly,
            sortOrder = GallerySortOrder.DATE_NEWEST,
            routeAlbumFolderKey = null,
            routePlaceMatchKey = null,
            routePlaceTitle = null,
            mediaCacheRepository = repo,
        )
        assertEquals(listOf("a", "b", "c"), result.map { it.canonicalPath })
    }

    @Test
    fun photosTab_listsLocalPhotoAndCachedCloudThumbWithoutStattingDisk() {
        val cloudWithoutThumb = GalleryDisplayItem(
            canonicalPath = "cloud-empty",
            displayName = "empty.jpg",
            albumName = "Camera",
            kind = MediaKind.IMAGE,
            cloudState = CloudSyncState.CLOUD_ONLY,
            localMediaId = null,
            oneDriveItemId = "remote-empty",
            pendingDeleteTarget = PendingDeleteTarget.NONE,
            localUri = null,
            cachedOriginal = null,
            cachedPlaceholderThumb = null,
            sortTimestamp = 2L,
            showInBrowseGrid = false,
        )
        val cloudWithThumb = GalleryDisplayItem(
            canonicalPath = "cloud-thumb",
            displayName = "thumb.jpg",
            albumName = "Camera",
            kind = MediaKind.IMAGE,
            cloudState = CloudSyncState.CLOUD_ONLY,
            localMediaId = null,
            oneDriveItemId = "remote-thumb",
            pendingDeleteTarget = PendingDeleteTarget.NONE,
            localUri = null,
            cachedOriginal = null,
            cachedPlaceholderThumb = File("missing-on-purpose.jpg"),
            sortTimestamp = 3L,
            showInBrowseGrid = true,
        )
        val result = resolveBrowseGridItems(
            requiresActiveBrowseFilter = false,
            libraryItems = listOf(item("local", "Camera"), cloudWithoutThumb, cloudWithThumb),
            browseItemsFromState = emptyList(),
            sortOrder = GallerySortOrder.DATE_NEWEST,
            routeAlbumFolderKey = null,
            routePlaceMatchKey = null,
            routePlaceTitle = null,
            mediaCacheRepository = repo,
        )
        assertEquals(listOf("local", "cloud-thumb"), result.map { it.canonicalPath })
    }

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
            mediaCacheRepository = repo,
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
            mediaCacheRepository = repo,
        )
        assertTrue(browseGridItemsAlbumFolderMatches(result, "DMSO"))
    }

    @Test
    fun albumRoute_dropsCloudVideoWithoutPoster() {
        val cloudVideo = GalleryDisplayItem(
            canonicalPath = "v.mp4",
            displayName = "v.mp4",
            albumName = "Camera",
            kind = MediaKind.VIDEO,
            cloudState = CloudSyncState.CLOUD_ONLY,
            localMediaId = null,
            oneDriveItemId = "remote",
            pendingDeleteTarget = PendingDeleteTarget.NONE,
            localUri = null,
            cachedOriginal = null,
            sortTimestamp = 3L,
        )
        val photo = item("p", "Camera")
        val library = listOf(photo, cloudVideo)
        val staleState = listOf(cloudVideo, photo)
        val result = resolveBrowseGridItems(
            requiresActiveBrowseFilter = true,
            libraryItems = library,
            browseItemsFromState = staleState,
            sortOrder = GallerySortOrder.DATE_NEWEST,
            routeAlbumFolderKey = "Camera",
            routePlaceMatchKey = null,
            routePlaceTitle = null,
            mediaCacheRepository = repo,
        )
        assertEquals(1, result.size)
        assertEquals("p", result.single().canonicalPath)
    }
}
