package com.onegallery.sync.sync

import com.onegallery.sync.ui.gallery.GalleryGridScrollGate
import com.onegallery.sync.ui.gallery.routeKeepsSyncQuiet
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailPrefetchRunnerAdaptiveTest {

    @Test
    fun adaptiveParallelSlots_dropsToOneWhileTheGalleryIsInUseOrScrolling() {
        GalleryGridScrollGate.isUserScrollingGrid = false
        GalleryGridScrollGate.galleryInUse = false
        try {
            assertEquals(8, ThumbnailPrefetchRunner.adaptiveParallelSlots(8))
            GalleryGridScrollGate.isUserScrollingGrid = true
            assertEquals(1, ThumbnailPrefetchRunner.adaptiveParallelSlots(8))
            GalleryGridScrollGate.isUserScrollingGrid = false
            GalleryGridScrollGate.galleryInUse = true
            assertEquals(1, ThumbnailPrefetchRunner.adaptiveParallelSlots(8))
        } finally {
            GalleryGridScrollGate.isUserScrollingGrid = false
            GalleryGridScrollGate.galleryInUse = false
        }
    }

    @Test
    fun syncScreenAndSettingsKeepTheFastThumbnailPath() {
        assertTrue(routeKeepsSyncQuiet("gallery"))
        assertTrue(routeKeepsSyncQuiet("albums"))
        assertTrue(routeKeepsSyncQuiet("media/photo.jpg"))
        assertFalse(routeKeepsSyncQuiet("settings"))
        assertFalse(routeKeepsSyncQuiet("settings/sync"))
        assertFalse(routeKeepsSyncQuiet(null))
    }
}
