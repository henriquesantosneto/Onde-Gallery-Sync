package com.onegallery.sync.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.onegallery.sync.sync.PendingDeleteTarget
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncMetadataDao {
    @Query("SELECT * FROM sync_metadata ORDER BY COALESCE(localModifiedAt, remoteModifiedAt) DESC")
    fun observeAll(): Flow<List<SyncMetadataEntity>>

    @Query("SELECT * FROM sync_metadata")
    suspend fun getAll(): List<SyncMetadataEntity>

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

    @Query(
        """
        SELECT
            (SELECT COUNT(*) FROM sync_metadata) AS total,
            (SELECT COUNT(*) FROM sync_metadata WHERE localMediaId IS NOT NULL) AS withLocal,
            (SELECT COUNT(*) FROM sync_metadata WHERE cloudState = 'SYNCED') AS synced,
            (SELECT COUNT(*) FROM sync_metadata WHERE cloudState = 'PENDING_UPLOAD') AS pendingUpload,
            (SELECT COUNT(*) FROM sync_metadata WHERE cloudState = 'PENDING_DOWNLOAD') AS pendingDownload,
            (SELECT COUNT(*) FROM sync_metadata WHERE cloudState = 'CLOUD_ONLY') AS cloudOnly,
            (SELECT COUNT(*) FROM sync_metadata WHERE cloudState = 'LOCAL_ONLY') AS localOnly,
            (SELECT COUNT(*) FROM sync_metadata WHERE cloudState = 'ERROR') AS errors,
            (SELECT COUNT(*) FROM sync_metadata WHERE cloudState = 'CONFLICT'
                OR pendingDeleteTarget != 'NONE') AS conflicts,
            (SELECT COUNT(*) FROM sync_metadata WHERE cloudState = 'ERROR'
                OR cloudState = 'CONFLICT'
                OR (lastError IS NOT NULL AND TRIM(lastError) != '')) AS attention
        """,
    )
    fun observeIndexStats(): Flow<SyncIndexStatsRow>

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
