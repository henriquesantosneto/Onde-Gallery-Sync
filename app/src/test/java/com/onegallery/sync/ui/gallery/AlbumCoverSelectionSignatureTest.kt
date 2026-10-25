package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.TestApplication
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class AlbumCoverSelectionSignatureTest {

    private lateinit var repo: MediaCacheRepository

    @Before
    fun setUp() {
        repo = MediaCacheRepository(org.robolectric.RuntimeEnvironment.getApplication())
    }

    private fun localPhoto(path: String, ts: Long, id: Long) = GalleryDisplayItem(
        canonicalPath = path,
        displayName = "p.jpg",
        albumName = "Camera",
        kind = MediaKind.IMAGE,
        cloudState = CloudSyncState.LOCAL_ONLY,
        localMediaId = id,
        oneDriveItemId = null,
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        localUri = Uri.parse("content://media/external/images/$id"),
        cachedOriginal = null,
        cachedPlaceholderThumb = galleryTestJpeg(),
        sortTimestamp = ts,
    )

    @Test
    fun signature_changesWhenNewerLocalPhotoBecomesCover() {
        val older = localPhoto("a.jpg", ts = 1L, id = 1L)
        val newer = localPhoto("b.jpg", ts = 2L, id = 2L)
        val sigOld = albumCoverSelectionSignature(listOf(older), repo)
        val sigNew = albumCoverSelectionSignature(listOf(older, newer), repo)
        assertNotEquals(sigOld, sigNew)
    }
}
