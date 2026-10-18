package com.onegallery.sync.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SyncMetadataDao {
    @Query("SELECT * FROM sync_metadata ORDER BY localModifiedAt DESC")
    fun observeAll(): Flow<List<SyncMetadataEntity>>

    @Query("SELECT * FROM sync_metadata WHERE localMediaId = :localMediaId LIMIT 1")
    suspend fun getByLocalId(localMediaId: Long): SyncMetadataEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: SyncMetadataEntity)

    @Query("DELETE FROM sync_metadata WHERE localMediaId = :localMediaId")
    suspend fun deleteByLocalId(localMediaId: Long)
}
