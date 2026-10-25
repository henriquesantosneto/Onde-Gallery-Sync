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
class GalleryAlbumListItemsTest {

    private lateinit var repo: MediaCacheRepository

    @Before
    fun setUp() {
        repo = MediaCacheRepository(
            org.robolectric.RuntimeEnvironment.getApplication(),
        )
    }

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

    private fun localPhoto(path: String) = GalleryDisplayItem(
        canonicalPath = path,
        displayName = "p.jpg",
        albumName = "Camera",
        kind = MediaKind.IMAGE,
        cloudState = CloudSyncState.LOCAL_ONLY,
        localMediaId = 1L,
        oneDriveItemId = null,
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        localUri = Uri.parse("content://media/external/images/1"),
        cachedOriginal = null,
        cachedPlaceholderThumb = galleryTestJpeg(),
        sortTimestamp = 2L,
    )

    @Test
    fun albumGrouping_prefersVisibleItems_andDropsCloudWithoutThumb() {
        val catalog = listOf(cloudVideo("a"), cloudVideo("b"))
        val photo = localPhoto("b")
        val visible = listOf(cloudVideo("a"), photo)
        assertEquals(
            listOf(photo.canonicalPath),
            itemsForAlbumListGrouping(catalog, visible, repo).map { it.canonicalPath },
        )
    }

    @Test
    fun albumGrouping_withThumbnailOnly_doesNotFallBackToFullCatalog() {
        val catalog = listOf(cloudVideo("a"), cloudVideo("b"))
        val grouped = itemsForAlbumListGrouping(
            catalog,
            visibleItems = emptyList(),
            mediaCacheRepository = repo,
            onlyItemsWithThumbnail = true,
        )
        assertTrue(grouped.isEmpty())
    }

    @Test
    fun albumGrouping_withoutThumbnailFilter_stillRequiresBrowsableCover() {
        val catalog = listOf(cloudVideo("a"))
        assertTrue(
            itemsForAlbumListGrouping(
                catalog,
                visibleItems = emptyList(),
                mediaCacheRepository = repo,
                onlyItemsWithThumbnail = false,
            ).isEmpty(),
        )
    }
}
