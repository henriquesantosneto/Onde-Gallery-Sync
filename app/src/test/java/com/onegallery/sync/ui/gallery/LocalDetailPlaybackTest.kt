package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.TestApplication
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class LocalDetailPlaybackTest {

    @Test
    fun localPhotoOpensFromTheDeviceFile() {
        val photo = item("foto.jpg", MediaKind.IMAGE, localUri = Uri.parse("content://media/1"))
        assertTrue(photo.showsFromLocalFile())
        assertFalse(photo.playsInlineLocalVideo())
    }

    @Test
    fun cloudPhotoStaysOnTheThumbnail() {
        val cloud = item(
            "nuvem.jpg",
            MediaKind.IMAGE,
            cloudState = CloudSyncState.CLOUD_ONLY,
            thumb = File("/cache/nuvem.jpg"),
        )
        assertFalse(cloud.showsFromLocalFile())
        assertFalse(cloud.playsInlineLocalVideo())
    }

    @Test
    fun previewChainSkipsPhotosAndCloudVideos() {
        val items = listOf(
            item("a.jpg", MediaKind.IMAGE, localUri = Uri.parse("content://media/1")),
            item("v1.mp4", MediaKind.VIDEO, localUri = Uri.parse("content://media/2")),
            item("meio.jpg", MediaKind.IMAGE, localUri = Uri.parse("content://media/3")),
            item(
                "nuvem.mp4",
                MediaKind.VIDEO,
                cloudState = CloudSyncState.CLOUD_ONLY,
                thumb = File("/cache/nuvem.mp4.jpg"),
            ),
            item("v2.mp4", MediaKind.VIDEO, localUri = Uri.parse("content://media/5")),
        )
        assertEquals(4, nextLocalDeviceVideoIndex(items, 1))
        assertNull(nextLocalDeviceVideoIndex(items, 4))
        assertTrue(items[1].playsInlineLocalVideo())
        assertFalse(items[3].playsInlineLocalVideo())
    }

    @Test
    fun gridPreviewPlaysOnlyLocalVideosInOrder() {
        val items = listOf(
            item("a.jpg", MediaKind.IMAGE, localUri = Uri.parse("content://media/1")),
            item("v1.mp4", MediaKind.VIDEO, localUri = Uri.parse("content://media/2")),
            item(
                "nuvem.mp4",
                MediaKind.VIDEO,
                cloudState = CloudSyncState.CLOUD_ONLY,
                thumb = File("/cache/nuvem.jpg"),
            ),
            item("v2.mp4", MediaKind.VIDEO, localUri = Uri.parse("content://media/4")),
        )
        assertEquals(listOf("v1.mp4", "v2.mp4"), localVideosInPreviewOrder(items))
    }

    private fun item(
        name: String,
        kind: MediaKind,
        cloudState: CloudSyncState = CloudSyncState.SYNCED,
        localUri: Uri? = null,
        thumb: File? = null,
    ) = GalleryDisplayItem(
        canonicalPath = name,
        displayName = name,
        albumName = "Camera",
        kind = kind,
        cloudState = cloudState,
        localMediaId = if (localUri != null) 1L else null,
        oneDriveItemId = if (cloudState == CloudSyncState.CLOUD_ONLY) "od-$name" else null,
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        localUri = localUri,
        cachedOriginal = null,
        cachedPlaceholderThumb = thumb,
        sortTimestamp = 1L,
    )
}
