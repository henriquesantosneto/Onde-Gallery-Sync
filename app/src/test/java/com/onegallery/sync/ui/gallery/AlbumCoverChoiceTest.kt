package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.TestApplication
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class AlbumCoverChoiceTest {

    private val paintable: (GalleryDisplayItem) -> Boolean = { it.canonicalPath != "hidden" }

    @Test
    fun withoutSavedCover_picksNewestPaintable() {
        val items = listOf(photo("old", 1L), photo("hidden", 9L), photo("new", 5L))
        val picked = chooseStickyAlbumCover(items, remembered = null, nowEpochMs = 1_000L, isPaintable = paintable)
        assertEquals("new", picked?.canonicalPath)
    }

    @Test
    fun savedCover_ignoresNewerPhotoUntilIntervalElapses() {
        val saved = AlbumCoverChoice("old", sortTimestamp = 1L, chosenAtEpochMs = 1_000L)
        val items = listOf(photo("old", 1L), photo("new", 8L))
        val held = chooseStickyAlbumCover(
            items,
            saved,
            nowEpochMs = saved.chosenAtEpochMs + 30_000L,
            isPaintable = paintable,
        )
        assertEquals("old", held?.canonicalPath)
        val replaced = chooseStickyAlbumCover(
            items,
            saved,
            nowEpochMs = saved.chosenAtEpochMs + ALBUM_COVER_REPLACE_INTERVAL_MS,
            isPaintable = paintable,
        )
        assertEquals("new", replaced?.canonicalPath)
    }

    @Test
    fun missingSavedCover_isReplacedImmediately() {
        val saved = AlbumCoverChoice("gone", sortTimestamp = 1L, chosenAtEpochMs = 5_000L)
        val picked = chooseStickyAlbumCover(
            listOf(photo("only", 2L)),
            saved,
            nowEpochMs = saved.chosenAtEpochMs + 1L,
            isPaintable = paintable,
        )
        assertEquals("only", picked?.canonicalPath)
    }

    @Test
    fun store_remembersChoiceAcrossReload() {
        val dir = File.createTempFile("covers", ".dir")
        dir.delete()
        dir.mkdirs()
        val file = File(dir, "album-cover-choices-v1.txt")
        val store = AlbumCoverChoiceStore()
        val first = store.choose("album:Camera", listOf(photo("old", 1L), photo("new", 3L)), 10_000L, paintable)
        assertEquals("new", first?.canonicalPath)
        store.saveIfDirty(file)

        val restored = AlbumCoverChoiceStore()
        restored.load(file)
        val held = restored.choose(
            "album:Camera",
            listOf(photo("old", 1L), photo("new", 3L), photo("newer", 9L)),
            20_000L,
            paintable,
        )
        assertEquals("new", held?.canonicalPath)
        assertNull(restored.remembered("album:Other"))
        dir.deleteRecursively()
    }

    @Test
    fun cheapSource_skipsShortPlaceholderKeepsRealThumbAndLocalPhoto() {
        val tiny = File.createTempFile("stub", ".jpg")
        tiny.writeBytes(ByteArray(400))
        val real = File.createTempFile("real", ".jpg")
        real.writeBytes(ByteArray(9_000))
        val placeholderItem = photo("cloud", 5L).copy(cachedPlaceholderThumb = tiny)
        val realItem = photo("cloud2", 4L).copy(cachedPlaceholderThumb = real)
        val local = photo("local", 1L).copy(
            localUri = Uri.parse("content://media/external/images/media/3"),
        )
        assertEquals(false, placeholderItem.hasCheapAlbumCoverSource())
        assertEquals(true, realItem.hasCheapAlbumCoverSource())
        assertEquals(true, local.hasCheapAlbumCoverSource())
        tiny.delete()
        real.delete()
    }

    private fun photo(path: String, timestamp: Long) = GalleryDisplayItem(
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
        sortTimestamp = timestamp,
    )
}
