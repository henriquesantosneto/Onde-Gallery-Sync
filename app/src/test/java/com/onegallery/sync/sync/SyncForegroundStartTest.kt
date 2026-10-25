package com.onegallery.sync.sync

import com.onegallery.sync.TestApplication
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class SyncForegroundStartTest {

    @Test
    fun runningStart_staysUp() {
        assertFalse(
            shouldShutdownForegroundService(
                commandGeneration = 1,
                latestGeneration = 1,
                stopAtGeneration = 0,
                actionStop = false,
            ),
        )
    }

    @Test
    fun stopBeforeForeground_shutsDownOnlyAfterPromote() {
        assertTrue(
            shouldShutdownForegroundService(
                commandGeneration = 4,
                latestGeneration = 4,
                stopAtGeneration = 4,
                actionStop = false,
            ),
        )
    }

    @Test
    fun newerStart_ignoresPreviousStop() {
        assertFalse(
            shouldShutdownForegroundService(
                commandGeneration = 6,
                latestGeneration = 6,
                stopAtGeneration = 5,
                actionStop = false,
            ),
        )
        assertFalse(
            shouldShutdownForegroundService(
                commandGeneration = 5,
                latestGeneration = 6,
                stopAtGeneration = 5,
                actionStop = true,
            ),
        )
    }

    @Test
    fun currentStop_shutsDown() {
        assertTrue(
            shouldShutdownForegroundService(
                commandGeneration = 3,
                latestGeneration = 3,
                stopAtGeneration = 3,
                actionStop = true,
            ),
        )
    }
}
