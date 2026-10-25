package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.TestApplication
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
