package com.onegallery.sync.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class AlbumCoverDiskTest {
    @Test
    fun stampRoundTripsRevisionVideoAndPath() {
        val stamp = AlbumCoverDiskStamp(
            revision = 42L,
            isVideo = true,
            cacheKey = "/DCIM/Camera/clip.mp4",
        )
        val decoded = decodeAlbumCoverStamp(encodeAlbumCoverStamp(stamp))
        assertEquals(stamp, decoded)
        assertNull(decodeAlbumCoverStamp("nope"))
        assertFalse(albumCoverDiskName("album:Férias").isBlank())
        assertEquals(albumCoverDiskName("album:Férias"), albumCoverDiskName("album:Férias"))
        assertFalse(albumCoverDiskName("album:A") == albumCoverDiskName("album:B"))
    }

    @Test
    fun coverBudgetIsASliceOfTheLargeHeap() {
        assertEquals(85 * 1024 * 1024, albumCoverCacheBytes(512))
        assertEquals(48 * 1024 * 1024, albumCoverCacheBytes(256))
        assertEquals(48 * 1024 * 1024, albumCoverCacheBytes(128))
        assertEquals(96 * 1024 * 1024, albumCoverCacheBytes(900))
    }
}
