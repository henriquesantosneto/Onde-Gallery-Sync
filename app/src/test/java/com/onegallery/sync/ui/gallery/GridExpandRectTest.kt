package com.onegallery.sync.ui.gallery

import org.junit.Assert.assertEquals
import org.junit.Test

class GridExpandRectTest {

    @Test
    fun widePhotoFitsTheWidthAndStaysCentered() {
        val rect = fittedExpandRect(
            contentWidth = 400f,
            contentHeight = 200f,
            boundsWidth = 200f,
            boundsHeight = 400f,
        )
        assertEquals(0f, rect.left, 0.01f)
        assertEquals(150f, rect.top, 0.01f)
        assertEquals(200f, rect.width, 0.01f)
        assertEquals(100f, rect.height, 0.01f)
    }

    @Test
    fun halfwayBetweenTheCellAndTheOpenedPhoto() {
        val rect = lerpExpandRect(
            start = ExpandRect(10f, 20f, 110f, 120f),
            end = ExpandRect(0f, 100f, 200f, 300f),
            fraction = 0.5f,
        )
        assertEquals(5f, rect.left, 0.01f)
        assertEquals(60f, rect.top, 0.01f)
        assertEquals(155f, rect.right, 0.01f)
        assertEquals(210f, rect.bottom, 0.01f)
    }
}
