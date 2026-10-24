package com.onegallery.sync.ui.gallery

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
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class GalleryBrowseVisibilityFilterVideoTest {

    private lateinit var repo: MediaCacheRepository

    @Before
    fun setUp() {
        repo = MediaCacheRepository(RuntimeEnvironment.getApplication())
    }

    @Test
    fun cloudVideoWithoutThumb_notListed_evenInLargeLibraryMode() {
        val item = GalleryDisplayItem(
            canonicalPath = "cloud/v.mp4",
            displayName = "v.mp4",
            albumName = "cloud",
            kind = MediaKind.VIDEO,
            cloudState = CloudSyncState.CLOUD_ONLY,
            localMediaId = null,
            oneDriveItemId = "remote-id",
            pendingDeleteTarget = PendingDeleteTarget.NONE,
            localUri = null,
            cachedOriginal = null,
            sortTimestamp = 1L,
            showInBrowseGrid = true,
        )
        val largeList = List(1_500) { idx ->
            if (idx == 0) item else item.copy(canonicalPath = "x$idx.mp4", showInBrowseGrid = false)
        }
        val visible = filterVisibleForGalleryBrowse(largeList, repo, onlyItemsWithThumbnail = false)
        assertTrue(visible.none { it.canonicalPath == "cloud/v.mp4" })
        assertEquals(0, visible.count { it.kind == MediaKind.VIDEO && it.oneDriveItemId != null })
    }
}
