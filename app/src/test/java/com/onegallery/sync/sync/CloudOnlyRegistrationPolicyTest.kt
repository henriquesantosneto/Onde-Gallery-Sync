package com.onegallery.sync.sync

import com.onegallery.sync.TestApplication
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.data.onedrive.RemoteDriveFile
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class CloudOnlyRegistrationPolicyTest {

    @Test
    fun indexedCloudFileWithoutGps_isNotRegisteredAgain() {
        val remote = remote()
        val existing = row(remote).copy(latitude = null, longitude = null, locationLabel = null)
        assertFalse(
            shouldRegisterCloudOnlyItem(
                path = remote.canonicalPath,
                remote = remote,
                existing = existing,
                onDevicePaths = emptySet(),
                userDeletedPaths = emptySet(),
            ),
        )
    }

    @Test
    fun newCloudFile_isRegistered() {
        val remote = remote()
        assertTrue(
            shouldRegisterCloudOnlyItem(
                path = remote.canonicalPath,
                remote = remote,
                existing = null,
                onDevicePaths = emptySet(),
                userDeletedPaths = emptySet(),
            ),
        )
    }

    @Test
    fun changedHash_isRegisteredAgain() {
        val remote = remote(hash = "novo")
        val existing = row(remote(hash = "antigo"))
        assertTrue(
            shouldRegisterCloudOnlyItem(
                path = remote.canonicalPath,
                remote = remote,
                existing = existing,
                onDevicePaths = emptySet(),
                userDeletedPaths = emptySet(),
            ),
        )
    }

    @Test
    fun fileOnTheDeviceOrDeleted_isSkipped() {
        val remote = remote()
        assertFalse(
            shouldRegisterCloudOnlyItem(
                path = remote.canonicalPath,
                remote = remote,
                existing = null,
                onDevicePaths = setOf(remote.canonicalPath),
                userDeletedPaths = emptySet(),
            ),
        )
        assertFalse(
            shouldRegisterCloudOnlyItem(
                path = remote.canonicalPath,
                remote = remote,
                existing = null,
                onDevicePaths = emptySet(),
                userDeletedPaths = setOf(remote.canonicalPath),
            ),
        )
    }

    private fun remote(hash: String = "hash") = RemoteDriveFile(
        itemId = "item-1",
        name = "foto.jpg",
        canonicalPath = "Camera/foto.jpg",
        modifiedAtEpoch = 1_700_000_000L,
        sizeBytes = 1200L,
        quickXorHash = hash,
    )

    private fun row(remote: RemoteDriveFile) = SyncMetadataEntity(
        canonicalPath = remote.canonicalPath,
        displayName = remote.name,
        albumName = "Camera",
        localMediaId = null,
        localContentUri = null,
        oneDriveItemId = remote.itemId,
        localModifiedAt = null,
        remoteModifiedAt = remote.modifiedAtEpoch,
        remoteQuickXorHash = remote.quickXorHash,
        remoteSizeBytes = remote.sizeBytes,
        cloudState = CloudSyncState.CLOUD_ONLY,
        cachedFilePath = null,
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        lastError = null,
        latitude = 1.0,
        longitude = 2.0,
    )
}
