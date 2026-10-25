package com.onegallery.sync.worker

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailContinuationPolicyTest {

    @Test
    fun schedulesWhileItemsRemain() {
        assertTrue(
            ThumbnailContinuationPolicy.shouldScheduleAnother(
                moreRemain = true,
                setupComplete = true,
                backgroundEnabled = true,
                paused = false,
                userCancelled = false,
            ),
        )
    }

    @Test
    fun stopsWhenDrainedPausedOrCancelled() {
        assertFalse(
            ThumbnailContinuationPolicy.shouldScheduleAnother(
                moreRemain = false,
                setupComplete = true,
                backgroundEnabled = true,
                paused = false,
                userCancelled = false,
            ),
        )
        assertFalse(
            ThumbnailContinuationPolicy.shouldScheduleAnother(
                moreRemain = true,
                setupComplete = true,
                backgroundEnabled = true,
                paused = true,
                userCancelled = false,
            ),
        )
        assertFalse(
            ThumbnailContinuationPolicy.shouldScheduleAnother(
                moreRemain = true,
                setupComplete = true,
                backgroundEnabled = false,
                paused = false,
                userCancelled = false,
            ),
        )
        assertFalse(
            ThumbnailContinuationPolicy.shouldScheduleAnother(
                moreRemain = true,
                setupComplete = false,
                backgroundEnabled = true,
                paused = false,
                userCancelled = false,
            ),
        )
        assertFalse(
            ThumbnailContinuationPolicy.shouldScheduleAnother(
                moreRemain = true,
                setupComplete = true,
                backgroundEnabled = true,
                paused = false,
                userCancelled = true,
            ),
        )
    }

    @Test
    fun stallWaitsLongerThanABusyPass() {
        val stalled = ThumbnailContinuationPolicy.delaySeconds(stalled = true, busy = false)
        val busy = ThumbnailContinuationPolicy.delaySeconds(stalled = false, busy = true)
        val normal = ThumbnailContinuationPolicy.delaySeconds(stalled = false, busy = false)
        assertTrue(stalled > busy)
        assertTrue(busy > normal)
        assertEquals(ThumbnailContinuationPolicy.STALL_DELAY_SECONDS, stalled)
        assertEquals(ThumbnailContinuationPolicy.NORMAL_DELAY_SECONDS, normal)
    }

    @Test
    fun followUpAlternatesUniqueWork() {
        assertEquals(
            ThumbnailContinuationPolicy.FOLLOW_UP_UNIQUE,
            ThumbnailContinuationPolicy.followUpUniqueName(ThumbnailContinuationPolicy.SLOT_PRIMARY),
        )
        assertEquals(
            ThumbnailContinuationPolicy.PRIMARY_UNIQUE,
            ThumbnailContinuationPolicy.followUpUniqueName(ThumbnailContinuationPolicy.SLOT_FOLLOW_UP),
        )
        assertEquals(
            ThumbnailContinuationPolicy.PRIMARY_UNIQUE,
            ThumbnailContinuationPolicy.followUpUniqueName(null),
        )
        assertEquals(
            ThumbnailContinuationPolicy.SLOT_FOLLOW_UP,
            ThumbnailContinuationPolicy.slotForUniqueName(ThumbnailContinuationPolicy.FOLLOW_UP_UNIQUE),
        )
    }
}
