package com.onegallery.sync.data.local.db

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget

@Entity(
    tableName = "sync_metadata",
    indices = [
        Index(value = ["cloudState"]),
        Index(value = ["localMediaId"]),
        Index(value = ["oneDriveItemId"]),
    ],
)
data class SyncMetadataEntity(
    @PrimaryKey val canonicalPath: String,
    val displayName: String,
    val albumName: String,
    /** Álbum escolhido pelo usuário (organização na galeria; caminho na nuvem não muda). */
    val userAlbumName: String? = null,
    val localMediaId: Long?,
    val localContentUri: String?,
    val oneDriveItemId: String?,
    val localModifiedAt: Long?,
    /** Quando a foto/vídeo foi capturado (EXIF / DATE_TAKEN), em segundos UTC. */
    val capturedAtSeconds: Long? = null,
    val remoteModifiedAt: Long?,
    /** Graph `file.hashes.quickXorHash` (Base64) da cópia na nuvem. */
    val remoteQuickXorHash: String? = null,
    val remoteSizeBytes: Long? = null,
    /** Calculado no aparelho só quando necessário (candidatos a upload). */
    val localQuickXorHash: String? = null,
    val localSizeBytes: Long? = null,
    val cloudState: CloudSyncState,
    val cachedFilePath: String?,
    val pendingDeleteTarget: PendingDeleteTarget,
    val lastError: String?,
    val locationLabel: String? = null,
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** Duração do vídeo (MediaStore), em milissegundos. */
    val videoDurationMs: Long? = null,
    /** Liberar espaço: cópia local removida de propósito; não espelhar como exclusão na nuvem. */
    val localReleasedForSpace: Boolean = false,
)
