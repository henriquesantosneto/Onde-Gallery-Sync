package com.onegallery.sync.sync

import android.util.Base64
import com.onegallery.sync.data.local.db.SyncMetadataEntity
import com.onegallery.sync.data.onedrive.RemoteDriveFile
import java.io.File
import java.io.InputStream

/** Comparação quickXorHash + tamanho (estratégia equilibrada vs. reenvio por data). */
object ContentFingerprint {

    fun key(quickXorHashBase64: String, sizeBytes: Long): String =
        "${normalizeHash(quickXorHashBase64)}:$sizeBytes"

    fun normalizeHash(base64: String): String = base64.trim()

    fun hashesEqual(a: String?, b: String?): Boolean {
        if (a.isNullOrBlank() || b.isNullOrBlank()) return false
        return normalizeHash(a).equals(normalizeHash(b), ignoreCase = false)
    }

    fun sizesEqual(a: Long?, b: Long?): Boolean {
        if (a == null || b == null) return false
        return a == b
    }

    fun matchesRemote(localHash: String?, localSize: Long?, remote: RemoteDriveFile): Boolean {
        val remoteHash = remote.quickXorHash
        val remoteSize = remote.sizeBytes
        if (!hashesEqual(localHash, remoteHash)) return false
        return sizesEqual(localSize, remoteSize)
    }

    fun matchesIndexedLocal(row: SyncMetadataEntity, remote: RemoteDriveFile): Boolean =
        matchesRemote(row.localQuickXorHash, row.localSizeBytes, remote)

    fun quickXorHashBase64(input: InputStream): String {
        val bytes = QuickXorHash.digest(input)
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    fun quickXorHashBase64(file: File): String {
        file.inputStream().buffered().use { return quickXorHashBase64(it) }
    }
}
