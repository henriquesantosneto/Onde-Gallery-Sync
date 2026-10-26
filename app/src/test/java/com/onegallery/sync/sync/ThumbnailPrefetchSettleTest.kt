package com.onegallery.sync.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ThumbnailPrefetchSettleTest {

    @Test
    fun giveUpPlaceholderLeavesTheAutomaticQueue() {
        assertFalse(
            thumbnailStillNeedsPrefetch(
                canBuild = true,
                hasDisplayableThumb = false,
                hasGiveUpPlaceholder = true,
            ),
        )
    }

    @Test
    fun realThumbOrUnsupportedFileIsNotPending() {
        assertFalse(
            thumbnailStillNeedsPrefetch(
                canBuild = true,
                hasDisplayableThumb = true,
                hasGiveUpPlaceholder = false,
            ),
        )
        assertFalse(
            thumbnailStillNeedsPrefetch(
                canBuild = false,
                hasDisplayableThumb = false,
                hasGiveUpPlaceholder = false,
            ),
        )
    }

    @Test
    fun missingThumbStaysPendingUntilARealFileOrGiveUp() {
        assertTrue(
            thumbnailStillNeedsPrefetch(
                canBuild = true,
                hasDisplayableThumb = false,
                hasGiveUpPlaceholder = false,
            ),
        )
    }
}
