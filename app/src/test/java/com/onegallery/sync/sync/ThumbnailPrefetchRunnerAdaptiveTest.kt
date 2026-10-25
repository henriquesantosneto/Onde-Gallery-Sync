package com.onegallery.sync.sync

import com.onegallery.sync.ui.gallery.GalleryGridScrollGate
import org.junit.Assert.assertEquals
import org.junit.Test

class ThumbnailPrefetchRunnerAdaptiveTest {

    @Test
    fun adaptiveParallelSlots_reducesWhileGridScrolling() {
        GalleryGridScrollGate.isUserScrollingGrid = false
        assertEquals(8, ThumbnailPrefetchRunner.adaptiveParallelSlots(8))
        GalleryGridScrollGate.isUserScrollingGrid = true
        assertEquals(2, ThumbnailPrefetchRunner.adaptiveParallelSlots(8))
        GalleryGridScrollGate.isUserScrollingGrid = false
    }
}
