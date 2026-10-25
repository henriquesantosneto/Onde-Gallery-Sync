package com.onegallery.sync.ui.gallery

import android.graphics.Bitmap
import android.net.Uri
import com.onegallery.sync.TestApplication
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class AlbumCoverBitmapCacheTest {

    @Before
    fun clear() {
        AlbumCoverBitmapCache.clearForTest()
    }

    @Test
    fun seed_skipsUriAndVideoPosterAndKeepsShownFile() {
        val file = File("cover.jpg")
        val uri = Uri.parse("content://media/external/images/media/7")
        val video = DeviceVideoCover(Uri.parse("content://media/external/video/media/3"), 3L)
        assertNull(albumCoverDisplaySeed(cached = null, fallback = uri))
        assertNull(albumCoverDisplaySeed(cached = null, fallback = video))
        assertEquals(file, albumCoverDisplaySeed(cached = null, fallback = file))
        assertEquals(file, albumCoverDisplaySeed(cached = file, fallback = uri))
        assertFalse(uri.isPaintableAlbumCoverModel())
        assertFalse(video.isPaintableAlbumCoverModel())
    }

    @Test
    fun failedResolve_keepsCoverAlreadyOnScreen() {
        val shown = File("shown.jpg")
        val uri = Uri.parse("content://media/external/images/media/9")
        assertEquals(shown, nextAlbumCoverModel(current = shown, resolved = null, fallback = null))
        assertEquals(shown, nextAlbumCoverModel(current = shown, resolved = uri, fallback = File("other.jpg")))
        assertEquals(File("newer.jpg"), nextAlbumCoverModel(current = shown, resolved = File("newer.jpg"), fallback = null))
        assertNull(nextAlbumCoverModel(current = uri, resolved = null, fallback = uri))
    }

    @Test
    fun cache_roundTripsPaintableCoverAndRejectsUri() {
        val file = File("album-a.jpg")
        AlbumCoverBitmapCache.put("album:a", file, isVideo = false, cacheKey = "album-a.jpg", revision = 4L)
        val hit = AlbumCoverBitmapCache.get("album:a")
        assertEquals(file, hit?.model)
        assertEquals(4L, hit?.revision)
        assertEquals(false, hit?.empty)
        val uri = Uri.parse("content://media/external/images/media/1")
        AlbumCoverBitmapCache.put("album:uri", uri, isVideo = false, cacheKey = "uri", revision = 1L)
        assertNull(AlbumCoverBitmapCache.get("album:uri"))
    }

    @Test
    fun unresolved_doesNotReplaceAShownCover() {
        val file = File("kept.jpg")
        AlbumCoverBitmapCache.put("album:b", file, isVideo = true, cacheKey = "kept.jpg", revision = 1L)
        AlbumCoverBitmapCache.markUnresolved("album:b", 2L)
        val hit = AlbumCoverBitmapCache.get("album:b")
        assertEquals(file, hit?.model)
        assertEquals(1L, hit?.revision)
        assertTrue(hit?.isVideo == true)
    }

    @Test
    fun cache_dropsTheLeastRecentCoverPastTheCap() {
        AlbumCoverBitmapCache.put("album:0", File("cover-0.jpg"), false, "cover-0.jpg", 1L)
        for (index in 1..200) {
            AlbumCoverBitmapCache.put(
                "album:$index",
                File("cover-$index.jpg"),
                false,
                "cover-$index.jpg",
                1L,
            )
        }
        assertNull(AlbumCoverBitmapCache.get("album:0"))
        assertEquals(
            "cover-200.jpg",
            (AlbumCoverBitmapCache.get("album:200")?.model as File).name,
        )
    }

    @Test
    fun bitmapCover_isKeptUntilTheByteBudgetDropsIt() {
        val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        AlbumCoverBitmapCache.put("album:bmp", bitmap, isVideo = true, cacheKey = "clip", revision = 8L)
        val hit = AlbumCoverBitmapCache.get("album:bmp")
        assertTrue(hit?.model === bitmap)
        assertEquals(8L, hit?.revision)
        assertTrue(hit?.isVideo == true)
    }
}
