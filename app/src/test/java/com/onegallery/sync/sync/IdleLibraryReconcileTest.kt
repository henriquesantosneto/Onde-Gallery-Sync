package com.onegallery.sync.sync

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class IdleLibraryReconcileTest {

    @Test
    fun idleIncrementalSync_skipsItemByItemReconcile() {
        assertTrue(shouldSkipUnchangedLibraryReconcile(idle()))
    }

    @Test
    fun firstListingOrLocalChange_keepsFullReconcile() {
        assertFalse(shouldSkipUnchangedLibraryReconcile(idle(incrementalRemote = false)))
        assertFalse(shouldSkipUnchangedLibraryReconcile(idle(localLibraryMutated = true)))
    }

    @Test
    fun anyRemoteDelta_keepsFullReconcile() {
        assertFalse(shouldSkipUnchangedLibraryReconcile(idle(remoteChangedCount = 1)))
        assertFalse(shouldSkipUnchangedLibraryReconcile(idle(supplementalFromRecursive = 2)))
        assertFalse(shouldSkipUnchangedLibraryReconcile(idle(unresolvedDeltaSkips = 1)))
    }

    @Test
    fun pendingUploadOrDelete_keepsFullReconcile() {
        assertFalse(shouldSkipUnchangedLibraryReconcile(idle(pendingUploads = 1)))
        assertFalse(shouldSkipUnchangedLibraryReconcile(idle(pendingDeletes = 1)))
    }

    private fun idle(
        localLibraryMutated: Boolean = false,
        incrementalRemote: Boolean = true,
        remoteChangedCount: Int = 0,
        supplementalFromRecursive: Int = 0,
        unresolvedDeltaSkips: Int = 0,
        pendingUploads: Int = 0,
        pendingDeletes: Int = 0,
    ) = IdleReconcileSignals(
        localLibraryMutated = localLibraryMutated,
        incrementalRemote = incrementalRemote,
        remoteChangedCount = remoteChangedCount,
        supplementalFromRecursive = supplementalFromRecursive,
        unresolvedDeltaSkips = unresolvedDeltaSkips,
        pendingUploads = pendingUploads,
        pendingDeletes = pendingDeletes,
    )
}
