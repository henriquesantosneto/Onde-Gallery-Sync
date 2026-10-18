package com.onegallery.sync.data.local.db

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "user_albums")
data class UserAlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val createdAtEpoch: Long,
)
