package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = com.onegallery.sync.TestApplication::class)
class GalleryCloudOverlayStateTest {

    private val context = RuntimeEnvironment.getApplication()

    private fun item(
        cloudState: CloudSyncState,
        oneDriveItemId: String? = null,
        localUri: Uri? = null,
        cachedOriginal: File? = null,
        localReleasedForSpace: Boolean = false,
    ) = GalleryDisplayItem(
        canonicalPath = "/Photos/test.jpg",
        displayName = "test.jpg",
        albumName = "Photos",
        kind = MediaKind.IMAGE,
        cloudState = cloudState,
        localMediaId = null,
        oneDriveItemId = oneDriveItemId,
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        localUri = localUri,
        cachedOriginal = cachedOriginal,
        sortTimestamp = 1L,
        localReleasedForSpace = localReleasedForSpace,
    )

    @Test
    fun cloudOnly_showsCloud() {
        val state = galleryCloudOverlayState(
            item(CloudSyncState.CLOUD_ONLY, oneDriveItemId = "abc"),
            context,
        )
        assertEquals(CloudSyncState.CLOUD_ONLY, state)
    }

    @Test
    fun syncedWithLocalOriginal_showsCloudDone() {
        val file = File(context.cacheDir, "orig.jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }
        val state = galleryCloudOverlayState(
            item(
                CloudSyncState.SYNCED,
                oneDriveItemId = "abc",
                cachedOriginal = file,
            ),
            context,
        )
        assertEquals(CloudSyncState.SYNCED, state)
    }

    @Test
    fun syncedMetadataWithoutLocalFile_showsCloud() {
        val state = galleryCloudOverlayState(
            item(CloudSyncState.SYNCED, oneDriveItemId = "abc"),
            context,
        )
        assertEquals(CloudSyncState.CLOUD_ONLY, state)
    }

    @Test
    fun pendingUpload_showsPhone() {
        val file = File(context.cacheDir, "local.jpg").apply { writeBytes(byteArrayOf(1)) }
        val state = galleryCloudOverlayState(
            item(
                CloudSyncState.PENDING_UPLOAD,
                cachedOriginal = file,
            ),
            context,
        )
        assertEquals(CloudSyncState.LOCAL_ONLY, state)
    }

    @Test
    fun localOnlyNotInCloud_showsPhone() {
        val file = File(context.cacheDir, "only-local.jpg").apply { writeBytes(byteArrayOf(1)) }
        val state = galleryCloudOverlayState(
            item(CloudSyncState.LOCAL_ONLY, cachedOriginal = file),
            context,
        )
        assertEquals(CloudSyncState.LOCAL_ONLY, state)
    }
}
