package com.onegallery.sync.ui.gallery

import com.onegallery.sync.TestApplication
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import org.junit.Assert.assertEquals
import com.onegallery.sync.sync.PendingDeleteTarget
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class GalleryGridFrameTest {

    @Test
    fun identicalRowsStayOnThePublishedFrame() {
        val frame = frame(listOf(photo("a.jpg", null)))
        assertTrue(samePhotoGridFrame(frame, frame(listOf(photo("a.jpg", null)))))
    }

    @Test
    fun aNewThumbnailIsOneFrameChange() {
        val current = frame(listOf(photo("a.jpg", null)))
        val next = frame(listOf(photo("a.jpg", File("/cache/a.jpg"))))
        assertFalse(samePhotoGridFrame(current, next))
    }

    @Test
    fun gridBadgeUsesTheItemWithoutAskingMediaStore() {
        val synced = photo("a.jpg", null).copy(
            oneDriveItemId = "remote",
            cloudState = CloudSyncState.SYNCED,
            localMediaId = 7L,
        )
        val cloud = photo("b.jpg", null).copy(
            oneDriveItemId = "remote",
            cloudState = CloudSyncState.CLOUD_ONLY,
            localMediaId = null,
            localUri = null,
        )
        val local = photo("c.jpg", null).copy(cloudState = CloudSyncState.LOCAL_ONLY)
        assertEquals(CloudSyncState.SYNCED, galleryGridCloudBadge(synced))
        assertEquals(CloudSyncState.CLOUD_ONLY, galleryGridCloudBadge(cloud))
        assertEquals(CloudSyncState.LOCAL_ONLY, galleryGridCloudBadge(local))
        val syncedWithoutLocalFile = photo("d.jpg", null).copy(
            oneDriveItemId = "remote",
            cloudState = CloudSyncState.SYNCED,
            localMediaId = null,
            localUri = null,
        )
        assertEquals(CloudSyncState.SYNCED, galleryGridCloudBadge(syncedWithoutLocalFile))
        assertEquals(
            CloudSyncState.SYNCED,
            mergeGalleryGridBadge(CloudSyncState.SYNCED, CloudSyncState.CLOUD_ONLY),
        )
    }

    @Test
    fun syncStatusChangePublishesANewGridFrame() {
        val current = frame(listOf(photo("a.jpg", null)))
        val synced = frame(
            listOf(
                photo("a.jpg", null).copy(
                    cloudState = CloudSyncState.SYNCED,
                    oneDriveItemId = "remote",
                ),
            ),
        )
        assertFalse(samePhotoGridFrame(current, synced))
    }

    @Test
    fun sortAndAlbumFilterSwapImmediately() {
        val current = frame(listOf(photo("a.jpg", null)))
        val resorted = current.copy(sortOrder = GallerySortOrder.DATE_OLDEST)
        val filtered = current.copy(albumFilter = "Camera")
        assertTrue(resorted.replacesPhotoGridImmediately(current))
        assertTrue(filtered.replacesPhotoGridImmediately(current))
        assertFalse(current.replacesPhotoGridImmediately(current))
    }

    private fun frame(items: List<GalleryDisplayItem>) = GalleryGridPublication(
        libraryItems = items,
        items = items,
    )

    private fun photo(path: String, thumb: File?) = GalleryDisplayItem(
        canonicalPath = path,
        displayName = path,
        albumName = "Camera",
        kind = MediaKind.IMAGE,
        cloudState = CloudSyncState.LOCAL_ONLY,
        localMediaId = 1L,
        oneDriveItemId = null,
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        localUri = null,
        cachedOriginal = null,
        cachedPlaceholderThumb = thumb,
        sortTimestamp = 1L,
    )
}
