package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.TestApplication
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class AlbumCoverLocalVideoTest {

    private lateinit var repo: MediaCacheRepository

    @Before
    fun setUp() {
        repo = MediaCacheRepository(org.robolectric.RuntimeEnvironment.getApplication())
    }

    private fun syncedLocalVideo(path: String) = GalleryDisplayItem(
        canonicalPath = path,
        displayName = "clip.mp4",
        albumName = "Camera",
        kind = MediaKind.VIDEO,
        cloudState = CloudSyncState.SYNCED,
        localMediaId = 42L,
        oneDriveItemId = "remote-1",
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        localUri = Uri.parse("content://media/external/video/42"),
        cachedOriginal = null,
        sortTimestamp = 100L,
    )

    @Test
    fun syncedLocalVideo_withoutPoster_notBrowsableUntilSync() {
        val item = syncedLocalVideo("v.mp4")
        assertFalse(item.hasBrowsableThumbnail(repo))
        assertFalse(item.isEligibleForAlbumCover(repo))
    }

    @Test
    fun resolveAlbumCover_usesCommittedJpeg() {
        val jpeg = galleryTestJpeg()
        val item = syncedLocalVideo("v.mp4").copy(cachedPlaceholderThumb = jpeg)
        val cover = resolveAlbumCoverItem(listOf(item), repo)
        assertEquals(item.canonicalPath, cover?.canonicalPath)
        assertEquals(jpeg, resolveGalleryCoverModel(cover!!, repo))
    }

    @Test
    fun cloudOnlyVideo_withoutPoster_notEligibleForCover() {
        val item = GalleryDisplayItem(
            canonicalPath = "cloud/v.mp4",
            displayName = "v.mp4",
            albumName = "Camera",
            kind = MediaKind.VIDEO,
            cloudState = CloudSyncState.CLOUD_ONLY,
            localMediaId = null,
            oneDriveItemId = "remote",
            pendingDeleteTarget = PendingDeleteTarget.NONE,
            localUri = null,
            cachedOriginal = null,
            sortTimestamp = 1L,
        )
        assertFalse(item.isEligibleForAlbumCover(repo))
        org.junit.Assert.assertNull(resolveAlbumCoverItem(listOf(item), repo))
    }
}
