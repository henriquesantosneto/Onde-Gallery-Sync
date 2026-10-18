package com.onegallery.sync.data.local.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface UserAlbumDao {
    @Query("SELECT * FROM user_albums ORDER BY title COLLATE NOCASE ASC")
    fun observeAll(): Flow<List<UserAlbumEntity>>

    @Query("SELECT * FROM user_albums ORDER BY title COLLATE NOCASE ASC")
    suspend fun getAll(): List<UserAlbumEntity>

    @Query("SELECT * FROM user_albums WHERE title = :title LIMIT 1")
    suspend fun getByTitle(title: String): UserAlbumEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(album: UserAlbumEntity)

    @Query("DELETE FROM user_albums WHERE id = :id")
    suspend fun delete(id: String)
}
