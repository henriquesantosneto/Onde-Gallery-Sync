package com.onegallery.sync.ui.gallery

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ImmersiveToggleGestureTest {

    private val slop = 24f

    @Test
    fun tapMovement_notCancelled() {
        assertFalse(immersiveToggleCancelledByMovement(Offset(2f, 3f), slop))
    }

    @Test
    fun horizontalSwipe_cancelled() {
        assertTrue(immersiveToggleCancelledByMovement(Offset(12f, 1f), slop))
    }

    @Test
    fun verticalSwipe_cancelled() {
        assertTrue(immersiveToggleCancelledByMovement(Offset(1f, 12f), slop))
    }

    @Test
    fun longDrag_cancelled() {
        assertTrue(immersiveToggleCancelledByMovement(Offset(30f, 30f), slop))
    }
}
