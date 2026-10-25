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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
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
    fun syncedLocalVideo_withoutPoster_staysInGalleryAndCoverUsesMediaStore() {
        val item = syncedLocalVideo("v.mp4")
        assertTrue(item.hasBrowsableThumbnail(repo))
        assertTrue(item.isEligibleForAlbumCover(repo))
        val cover = resolveGalleryCoverModel(item, repo) as DeviceVideoCover
        assertEquals(item.localUri, cover.uri)
        assertEquals(item.localMediaId, cover.mediaId)
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

    @Test
    fun gridPaintFile_usesJpegNamedAsClipWithoutDecodingVideo() {
        val jpeg = galleryTestJpeg()
        val dest = repo.thumbFile("Camera/poster.mp4")
        dest.parentFile?.mkdirs()
        jpeg.copyTo(dest, overwrite = true)
        val item = syncedLocalVideo("Camera/poster.mp4")
        assertEquals(dest.absolutePath, gridPaintFile(item, repo)?.absolutePath)
        val videoBytes = ByteArray(256)
        videoBytes[4] = 'f'.code.toByte()
        videoBytes[5] = 't'.code.toByte()
        videoBytes[6] = 'y'.code.toByte()
        videoBytes[7] = 'p'.code.toByte()
        dest.writeBytes(videoBytes)
        assertNull(gridPaintFile(item, repo))
        dest.delete()
        jpeg.delete()
    }

    @Test
    fun videoFileIsNotLoadedAsImageBytes() {
        val video = File.createTempFile("clip", ".mp4")
        video.writeBytes(byteArrayOf(0, 0, 0, 0x18, 'f'.code.toByte(), 't'.code.toByte(), 'y'.code.toByte(), 'p'.code.toByte()))
        assertNull(video.asGalleryImageModel())
        video.delete()
    }

    @Test
    fun jpegSavedWithMp4Name_isInlineImage() {
        val jpeg = galleryTestJpeg()
        val renamed = File(jpeg.parentFile, "clip.mp4")
        jpeg.copyTo(renamed, overwrite = true)
        val model = renamed.asGalleryImageModel()
        assertTrue(model is ByteArray)
        renamed.delete()
        jpeg.delete()
    }
}
