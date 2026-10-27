package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.TestApplication
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class MediaShareTest {

    @Test
    fun contentUriIsPreferredOverMediaIdAndCache() {
        val file = tempJpeg()
        val item = item(
            localUri = Uri.parse("content://media/external/images/media/9"),
            localMediaId = 9L,
            cachedOriginal = file,
        )
        assertEquals(ShareLocalKind.CONTENT, shareLocalKind(item))
        file.delete()
    }

    @Test
    fun releasedLocalCopyFallsBackToCacheFile() {
        val file = tempJpeg()
        val item = item(
            localUri = Uri.parse("content://media/external/images/media/9"),
            localMediaId = 9L,
            localReleasedForSpace = true,
            cachedOriginal = file,
        )
        assertEquals(ShareLocalKind.CACHE_FILE, shareLocalKind(item))
        file.delete()
    }

    @Test
    fun mediaIdWhenThereIsNoContentUri() {
        assertEquals(ShareLocalKind.MEDIA_ID, shareLocalKind(item(localMediaId = 4L)))
    }

    @Test
    fun emptyCacheAndCloudOnlyStayOutOfTheShare() {
        val empty = File.createTempFile("share-empty", ".jpg")
        empty.writeBytes(ByteArray(0))
        assertEquals(ShareLocalKind.NONE, shareLocalKind(item(cachedOriginal = empty)))
        assertEquals(ShareLocalKind.NONE, shareLocalKind(item()))
        empty.delete()
    }

    @Test
    fun mimeFollowsASingleKindAndWidensWhenMixed() {
        assertEquals("image/*", shareIntentMime(listOf(MediaKind.IMAGE, MediaKind.IMAGE)))
        assertEquals("video/*", shareIntentMime(listOf(MediaKind.VIDEO)))
        assertEquals("*/*", shareIntentMime(listOf(MediaKind.IMAGE, MediaKind.VIDEO)))
    }

    @Test
    fun cloudFileIsSharedAndRecallStartsWhenTheOtherAppOpensIt() {
        val context = RuntimeEnvironment.getApplication()
        val cloud = item(path = "Fotos/nuvem.jpg").copy(
            oneDriveItemId = "remote-1",
            cloudState = CloudSyncState.CLOUD_ONLY,
        )
        assertEquals(ShareLocalKind.CLOUD_RECALL, shareLocalKind(cloud))
        val uri = shareRecallUri(context, cloud.canonicalPath)
        assertEquals("content", uri.scheme)
        assertTrue(uri.authority!!.endsWith(".sharerecall"))
        assertEquals(cloud.canonicalPath, canonicalPathFromShareRecallUri(uri))
    }

    @Test
    fun nothingSelectedOrOnlyInTheCloudExplainsWhy() {
        val context = RuntimeEnvironment.getApplication()
        assertEquals(
            "Selecione uma mídia para compartilhar.",
            shareGalleryMedia(context, emptyList(), emptyList()),
        )
        val cloud = item(path = "onedrive/photo.jpg")
        assertEquals(
            "Nada no aparelho para compartilhar. O arquivo ainda está só na nuvem.",
            shareGalleryMedia(context, listOf(cloud.canonicalPath), listOf(listOf(cloud))),
        )
    }

    private fun tempJpeg(): File =
        File.createTempFile("share", ".jpg").apply { writeBytes(byteArrayOf(1, 2, 3)) }

    private fun item(
        path: String = "local/photo.jpg",
        localUri: Uri? = null,
        localMediaId: Long? = null,
        cachedOriginal: File? = null,
        localReleasedForSpace: Boolean = false,
        kind: MediaKind = MediaKind.IMAGE,
    ) = GalleryDisplayItem(
        canonicalPath = path,
        displayName = path.substringAfterLast('/'),
        albumName = "Camera",
        kind = kind,
        cloudState = CloudSyncState.LOCAL_ONLY,
        localMediaId = localMediaId,
        oneDriveItemId = null,
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        localUri = localUri,
        cachedOriginal = cachedOriginal,
        sortTimestamp = 1L,
        localReleasedForSpace = localReleasedForSpace,
    )
}
