package com.onegallery.sync.data.local.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget

class SyncEnumConverters {
    @TypeConverter
    fun fromCloudState(value: CloudSyncState): String = value.name

    @TypeConverter
    fun toCloudState(value: String): CloudSyncState = CloudSyncState.valueOf(value)

    @TypeConverter
    fun fromPendingDelete(value: PendingDeleteTarget): String = value.name

    @TypeConverter
    fun toPendingDelete(value: String): PendingDeleteTarget = PendingDeleteTarget.valueOf(value)
}

@Database(
    entities = [SyncMetadataEntity::class, UserAlbumEntity::class],
    version = 6,
    exportSchema = false,
)
@TypeConverters(SyncEnumConverters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun syncMetadataDao(): SyncMetadataDao

    abstract fun userAlbumDao(): UserAlbumDao

    companion object {
        fun create(context: Context): AppDatabase {
            return Room.databaseBuilder(
                context.applicationContext,
                AppDatabase::class.java,
                "onegallery_sync.db",
            ).fallbackToDestructiveMigration()
                .build()
        }
    }
}
