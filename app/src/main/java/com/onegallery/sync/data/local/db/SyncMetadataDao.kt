package com.onegallery.sync.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.RawQuery
import androidx.sqlite.db.SupportSQLiteQuery
import com.onegallery.sync.sync.PendingDeleteTarget
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncMetadataDao {
    @Query("SELECT * FROM sync_metadata ORDER BY COALESCE(localModifiedAt, remoteModifiedAt) DESC")
    fun observeAll(): Flow<List<SyncMetadataEntity>>

    /** Índice da galeria — sem ORDER BY (ordenação fica na UI por [GalleryDisplayItem.sortTimestamp]). */
    @Query("SELECT * FROM sync_metadata")
    fun observeGalleryIndex(): Flow<List<SyncMetadataEntity>>

    @Query("SELECT * FROM sync_metadata")
    suspend fun getAll(): List<SyncMetadataEntity>

    @Query("SELECT COUNT(*) FROM sync_metadata")
    suspend fun countAll(): Int

    @Query("SELECT COUNT(*) FROM sync_metadata")
    fun observeMetadataCount(): Flow<Int>

    @Query("SELECT * FROM sync_metadata WHERE localMediaId IS NOT NULL")
    suspend fun getRowsWithLocalCopy(): List<SyncMetadataEntity>

    @Query(
        """
        SELECT * FROM sync_metadata
        WHERE localMediaId IS NOT NULL
          AND pendingDeleteTarget = 'NONE'
          AND cloudState = 'PENDING_UPLOAD'
        """,
    )
    suspend fun getPendingUploadRows(): List<SyncMetadataEntity>

    @Query(
        """
        SELECT COUNT(*) FROM sync_metadata
        WHERE cloudState = 'PENDING_UPLOAD'
           OR pendingDeleteTarget != 'NONE'
        """,
    )
    suspend fun countPendingOperations(): Int

    @Query(
        """
        SELECT COUNT(*) FROM sync_metadata
        WHERE cloudState = 'PENDING_UPLOAD' AND pendingDeleteTarget = 'NONE'
        """,
    )
    suspend fun countPendingUploadOperations(): Int

    @Query(
        """
        SELECT COUNT(*) FROM sync_metadata
        WHERE cloudState = 'PENDING_DOWNLOAD' AND pendingDeleteTarget = 'NONE'
        """,
    )
    suspend fun countPendingDownloadOperations(): Int

    @Query("SELECT * FROM sync_metadata WHERE canonicalPath = :canonicalPath LIMIT 1")
    suspend fun getByCanonicalPath(canonicalPath: String): SyncMetadataEntity?

    @Query("SELECT * FROM sync_metadata WHERE localMediaId = :localMediaId LIMIT 1")
    suspend fun getByLocalId(localMediaId: Long): SyncMetadataEntity?

    @Query("SELECT * FROM sync_metadata WHERE oneDriveItemId = :itemId LIMIT 1")
    suspend fun getByOneDriveId(itemId: String): SyncMetadataEntity?

    @Query(
        "SELECT COUNT(*) FROM sync_metadata WHERE cloudState = 'PENDING_UPLOAD' OR pendingDeleteTarget != 'NONE'",
    )
    fun observePendingCount(): Flow<Int>

    /** Itens na fila: envio, download ou exclusão pendente (ordem estável para a UI). */
    @Query(
        """
        SELECT * FROM sync_metadata
        WHERE cloudState = 'PENDING_UPLOAD'
           OR pendingDeleteTarget != 'NONE'
        ORDER BY
            CASE cloudState
                WHEN 'PENDING_UPLOAD' THEN 0
                ELSE 1
            END,
            COALESCE(localModifiedAt, remoteModifiedAt) DESC
        LIMIT 200
        """,
    )
    fun observePendingQueue(): Flow<List<SyncMetadataEntity>>

    @Query("SELECT * FROM sync_metadata WHERE pendingDeleteTarget != 'NONE'")
    fun observePendingDeletes(): Flow<List<SyncMetadataEntity>>

    @Query("SELECT * FROM sync_metadata WHERE pendingDeleteTarget != 'NONE'")
    suspend fun getPendingDeleteRows(): List<SyncMetadataEntity>

    @Query("SELECT COUNT(*) FROM sync_metadata WHERE pendingDeleteTarget != 'NONE'")
    suspend fun countPendingDeleteOperations(): Int

    @Query("SELECT * FROM sync_metadata WHERE cloudState = 'PENDING_DOWNLOAD'")
    suspend fun getPendingDownloadRows(): List<SyncMetadataEntity>

    @Query("SELECT COUNT(*) FROM sync_metadata WHERE cloudState = 'PENDING_DOWNLOAD'")
    suspend fun countPendingDownloadStates(): Int

    @Query(
        """
        SELECT * FROM sync_metadata
        WHERE oneDriveItemId IS NOT NULL AND TRIM(oneDriveItemId) != ''
        """,
    )
    suspend fun getRowsWithOneDriveId(): List<SyncMetadataEntity>

    @Query(
        """
        SELECT * FROM sync_metadata
        WHERE lastError IS NOT NULL AND TRIM(lastError) != ''
        """,
    )
    suspend fun getRowsWithLastError(): List<SyncMetadataEntity>

    @Query(
        """
        SELECT * FROM sync_metadata
        WHERE capturedAtSeconds IS NULL OR capturedAtSeconds <= 0
        """,
    )
    suspend fun getRowsMissingCaptureTime(): List<SyncMetadataEntity>

    @Query(
        """
        SELECT COUNT(*) FROM sync_metadata
        WHERE latitude IS NOT NULL AND longitude IS NOT NULL
        """,
    )
    suspend fun countRowsWithGps(): Int

    @Query(
        """
        SELECT * FROM sync_metadata
        WHERE (latitude IS NULL OR longitude IS NULL)
          AND (
            localMediaId IS NOT NULL
            OR (localContentUri IS NOT NULL AND TRIM(localContentUri) != '')
            OR (cachedFilePath IS NOT NULL AND TRIM(cachedFilePath) != '')
            OR (oneDriveItemId IS NOT NULL AND TRIM(oneDriveItemId) != '')
          )
        """,
    )
    suspend fun getRowsMissingGps(): List<SyncMetadataEntity>

    @Query(
        """
        SELECT COUNT(*) FROM sync_metadata
        WHERE (latitude IS NULL OR longitude IS NULL)
          AND (
            localMediaId IS NOT NULL
            OR (localContentUri IS NOT NULL AND TRIM(localContentUri) != '')
            OR (cachedFilePath IS NOT NULL AND TRIM(cachedFilePath) != '')
            OR (oneDriveItemId IS NOT NULL AND TRIM(oneDriveItemId) != '')
          )
        """,
    )
    suspend fun countRowsMissingGps(): Int

    @RawQuery
    suspend fun queryIndexStats(query: SupportSQLiteQuery): SyncIndexStatsRow

    @Query(
        """
        SELECT * FROM sync_metadata
        WHERE cloudState = 'ERROR'
           OR cloudState = 'CONFLICT'
           OR (lastError IS NOT NULL AND TRIM(lastError) != '')
        ORDER BY COALESCE(localModifiedAt, remoteModifiedAt) DESC
        LIMIT 25
        """,
    )
    fun observeAttentionItems(): Flow<List<SyncMetadataEntity>>

    /** Candidatos à fila pós-sync (sem varrer toda a tabela). */
    @Query(
        """
        SELECT * FROM sync_metadata WHERE
            pendingDeleteTarget != 'NONE'
            OR (
                oneDriveItemId IS NOT NULL
                AND cloudState IN ('PENDING_DOWNLOAD', 'CLOUD_ONLY')
            )
        """,
    )
    suspend fun getPostSyncResidualCandidates(): List<SyncMetadataEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncMetadataEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(entities: List<SyncMetadataEntity>)

    @Query("DELETE FROM sync_metadata WHERE canonicalPath = :canonicalPath")
    suspend fun deleteByCanonicalPath(canonicalPath: String)

    @Query("DELETE FROM sync_metadata")
    suspend fun deleteAll()

    @Query("UPDATE sync_metadata SET pendingDeleteTarget = :target WHERE canonicalPath = :canonicalPath")
    suspend fun updatePendingDelete(canonicalPath: String, target: PendingDeleteTarget)

    @Query("UPDATE sync_metadata SET userAlbumName = :album WHERE canonicalPath = :canonicalPath")
    suspend fun updateUserAlbum(canonicalPath: String, album: String?)
}
