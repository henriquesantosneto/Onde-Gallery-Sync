package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.onegallery.sync.TestApplication::class)
class GalleryBrowseVisibilityVideoTest {

    @Test
    fun cloudOnlyVideo_neverCountsAsLocalBrowseThumb() {
        val item = GalleryDisplayItem(
            canonicalPath = "v.mp4",
            displayName = "v.mp4",
            albumName = "A",
            kind = MediaKind.VIDEO,
            cloudState = CloudSyncState.CLOUD_ONLY,
            localMediaId = null,
            oneDriveItemId = "remote",
            pendingDeleteTarget = PendingDeleteTarget.NONE,
            localUri = null,
            cachedOriginal = null,
            sortTimestamp = 1L,
        )
        assertFalse(item.videoCountsAsLocalBrowseThumb())
    }

    @Test
    fun syncedCloudVideo_staleUriWithoutLocalCopy_notBrowsableAsLocal() {
        val item = GalleryDisplayItem(
            canonicalPath = "v.mp4",
            displayName = "v.mp4",
            albumName = "A",
            kind = MediaKind.VIDEO,
            cloudState = CloudSyncState.SYNCED,
            localMediaId = 99L,
            oneDriveItemId = "remote",
            pendingDeleteTarget = PendingDeleteTarget.NONE,
            localUri = Uri.parse("content://media/external/video/99"),
            cachedOriginal = null,
            sortTimestamp = 1L,
        )
        assertFalse(item.videoCountsAsLocalBrowseThumb())
    }

    @Test
    fun cloudOnlyImage_needsRecall_notLocalBrowseThumb() {
        val item = GalleryDisplayItem(
            canonicalPath = "p.jpg",
            displayName = "p.jpg",
            albumName = "A",
            kind = MediaKind.IMAGE,
            cloudState = CloudSyncState.CLOUD_ONLY,
            localMediaId = null,
            oneDriveItemId = "remote",
            pendingDeleteTarget = PendingDeleteTarget.NONE,
            localUri = null,
            cachedOriginal = null,
            sortTimestamp = 1L,
        )
        assertFalse(item.imageCountsAsLocalBrowseThumb())
    }

    @Test
    fun localOnlyVideo_withUri_countsAsLocalBrowseThumb() {
        val item = GalleryDisplayItem(
            canonicalPath = "v.mp4",
            displayName = "v.mp4",
            albumName = "A",
            kind = MediaKind.VIDEO,
            cloudState = CloudSyncState.LOCAL_ONLY,
            localMediaId = 1L,
            oneDriveItemId = null,
            pendingDeleteTarget = PendingDeleteTarget.NONE,
            localUri = Uri.parse("content://media/external/video/1"),
            cachedOriginal = null,
            sortTimestamp = 1L,
        )
        assertTrue(item.videoCountsAsLocalBrowseThumb())
    }
}
