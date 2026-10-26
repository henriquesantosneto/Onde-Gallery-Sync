package com.onegallery.sync.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PhotoGridSelectionTest {

    @Test
    fun fingerOnAPhotoReturnsThatPath() {
        val hits = listOf(
            GridSelectionHit(0, 0, 100, 40, null),
            GridSelectionHit(0, 40, 100, 100, "a.jpg"),
            GridSelectionHit(100, 40, 100, 100, "b.jpg"),
        )
        assertEquals("a.jpg", canonicalPathAtGridPoint(hits, 10f, 50f))
        assertEquals("b.jpg", canonicalPathAtGridPoint(hits, 150f, 80f))
    }

    @Test
    fun headerAndEmptySpaceStayUnselected() {
        val hits = listOf(
            GridSelectionHit(0, 0, 200, 48, null),
            GridSelectionHit(0, 48, 100, 100, "a.jpg"),
        )
        assertNull(canonicalPathAtGridPoint(hits, 20f, 10f))
        assertNull(canonicalPathAtGridPoint(hits, 180f, 80f))
    }

    @Test
    fun edgeOfTheCellStillBelongsToThePhoto() {
        val hits = listOf(GridSelectionHit(8, 48, 100, 100, "a.jpg"))
        assertEquals("a.jpg", canonicalPathAtGridPoint(hits, 8f, 48f))
        assertNull(canonicalPathAtGridPoint(hits, 108f, 48f))
        assertNull(canonicalPathAtGridPoint(hits, 8f, 148f))
    }
}
