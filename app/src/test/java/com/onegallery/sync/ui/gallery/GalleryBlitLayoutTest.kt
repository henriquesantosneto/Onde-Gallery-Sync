package com.onegallery.sync.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GalleryBlitLayoutTest {

    @Test
    fun headersSpanTheRowAndPhotosFillColumns() {
        val kinds = listOf("year", "a", "b", "c", "d")
        val layout = buildBlitLayout(
            count = kinds.size,
            isHeader = { kinds[it] == "year" },
            isYear = { kinds[it] == "year" },
            pathAt = { if (kinds[it] == "year") null else kinds[it] },
            titleAt = { if (kinds[it] == "year") "2024" else null },
            width = 340,
            cellMin = 100,
            gap = 4,
            padStart = 0,
            padEnd = 0,
            padTop = 0,
            padBottom = 0,
            headerHeight = 40,
            yearHeaderHeight = 50,
        )
        assertEquals(3, layout.columns)
        assertEquals(110, layout.cellSize)
        assertEquals("2024", layout.slots[0].title)
        assertEquals(50, layout.slots[0].height)
        assertEquals("a", blitPathAt(layout.slots, 10, 60))
        assertEquals("b", blitPathAt(layout.slots, 120, 60))
        assertEquals("d", blitPathAt(layout.slots, 10, 50 + 110 + 4 + 10))
        assertNull(blitPathAt(layout.slots, 10, 10))
    }

    @Test
    fun firstVisibleSkipsSlotsAboveTheScroll() {
        val layout = buildBlitLayout(
            count = 4,
            isHeader = { false },
            isYear = { false },
            pathAt = { "p$it" },
            titleAt = { null },
            width = 100,
            cellMin = 100,
            gap = 0,
            padStart = 0,
            padEnd = 0,
            padTop = 0,
            padBottom = 0,
            headerHeight = 10,
            yearHeaderHeight = 10,
        )
        assertEquals(0, blitFirstVisibleIndex(layout.slots, 0))
        assertEquals(2, blitFirstVisibleIndex(layout.slots, layout.slots[2].top + 1))
    }

    @Test
    fun fingerDownStopsCoastWithoutJumpingAndIgnoresThatTap() {
        val coast = BlitCoast()
        coast.onFlingStarted()
        assertTrue(coast.onFingerDown(eventTimeMs = 1_000L, scrollerActive = true))
        assertTrue(coast.ignoreTapAfterCoast)
        assertTrue(coast.fingerDown)
        assertFalse(coast.flinging)
        assertEquals(40, blitContentOffset(fingerDown = true, offset = 40, scrollerY = 900))

        assertFalse(coast.onFingerDown(eventTimeMs = 1_000L, scrollerActive = false))
        assertTrue(coast.ignoreTapAfterCoast)
        assertFalse(coast.onFingerDown(eventTimeMs = 1_020L, scrollerActive = false))
        assertTrue(coast.ignoreTapAfterCoast)

        coast.onFingerUp()
        assertFalse(coast.fingerDown)
        assertFalse(coast.onFingerDown(eventTimeMs = 1_400L, scrollerActive = false))
        assertFalse(coast.ignoreTapAfterCoast)
        assertEquals(900, blitContentOffset(fingerDown = false, offset = 40, scrollerY = 900))
    }

    @Test
    fun thumbTargetMatchesTheCellInsteadOfATinyPreview() {
        assertEquals(360, blitThumbTargetPx(120))
        assertEquals(480, blitThumbTargetPx(480))
        assertEquals(640, blitThumbTargetPx(1_200))
    }

    @Test
    fun pinchAbortsOnlyTheSelectionThisGestureOpened() {
        assertTrue(pinchAbortsSelectionOpenedByThisGesture(pointerCount = 2, openedSelectionThisGesture = true))
        assertFalse(pinchAbortsSelectionOpenedByThisGesture(pointerCount = 2, openedSelectionThisGesture = false))
        assertFalse(pinchAbortsSelectionOpenedByThisGesture(pointerCount = 1, openedSelectionThisGesture = true))
    }
}
