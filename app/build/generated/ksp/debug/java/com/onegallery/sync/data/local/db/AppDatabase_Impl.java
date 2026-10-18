package com.onegallery.sync.data.local.db;

import androidx.annotation.NonNull;
import androidx.room.DatabaseConfiguration;
import androidx.room.InvalidationTracker;
import androidx.room.RoomDatabase;
import androidx.room.RoomOpenHelper;
import androidx.room.migration.AutoMigrationSpec;
import androidx.room.migration.Migration;
import androidx.room.util.DBUtil;
import androidx.room.util.TableInfo;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.sqlite.db.SupportSQLiteOpenHelper;
import java.lang.Class;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.annotation.processing.Generated;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class AppDatabase_Impl extends AppDatabase {
  private volatile SyncMetadataDao _syncMetadataDao;

  @Override
  @NonNull
  protected SupportSQLiteOpenHelper createOpenHelper(@NonNull final DatabaseConfiguration config) {
    final SupportSQLiteOpenHelper.Callback _openCallback = new RoomOpenHelper(config, new RoomOpenHelper.Delegate(2) {
      @Override
      public void createAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("CREATE TABLE IF NOT EXISTS `sync_metadata` (`canonicalPath` TEXT NOT NULL, `displayName` TEXT NOT NULL, `albumName` TEXT NOT NULL, `localMediaId` INTEGER, `localContentUri` TEXT, `oneDriveItemId` TEXT, `localModifiedAt` INTEGER, `remoteModifiedAt` INTEGER, `cloudState` TEXT NOT NULL, `cachedFilePath` TEXT, `pendingDeleteTarget` TEXT NOT NULL, `lastError` TEXT, PRIMARY KEY(`canonicalPath`))");
        db.execSQL("CREATE TABLE IF NOT EXISTS room_master_table (id INTEGER PRIMARY KEY,identity_hash TEXT)");
        db.execSQL("INSERT OR REPLACE INTO room_master_table (id,identity_hash) VALUES(42, '033cbf7bc7d5574391b642d32d07917f')");
      }

      @Override
      public void dropAllTables(@NonNull final SupportSQLiteDatabase db) {
        db.execSQL("DROP TABLE IF EXISTS `sync_metadata`");
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onDestructiveMigration(db);
          }
        }
      }

      @Override
      public void onCreate(@NonNull final SupportSQLiteDatabase db) {
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onCreate(db);
          }
        }
      }

      @Override
      public void onOpen(@NonNull final SupportSQLiteDatabase db) {
        mDatabase = db;
        internalInitInvalidationTracker(db);
        final List<? extends RoomDatabase.Callback> _callbacks = mCallbacks;
        if (_callbacks != null) {
          for (RoomDatabase.Callback _callback : _callbacks) {
            _callback.onOpen(db);
          }
        }
      }

      @Override
      public void onPreMigrate(@NonNull final SupportSQLiteDatabase db) {
        DBUtil.dropFtsSyncTriggers(db);
      }

      @Override
      public void onPostMigrate(@NonNull final SupportSQLiteDatabase db) {
      }

      @Override
      @NonNull
      public RoomOpenHelper.ValidationResult onValidateSchema(
          @NonNull final SupportSQLiteDatabase db) {
        final HashMap<String, TableInfo.Column> _columnsSyncMetadata = new HashMap<String, TableInfo.Column>(12);
        _columnsSyncMetadata.put("canonicalPath", new TableInfo.Column("canonicalPath", "TEXT", true, 1, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSyncMetadata.put("displayName", new TableInfo.Column("displayName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSyncMetadata.put("albumName", new TableInfo.Column("albumName", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSyncMetadata.put("localMediaId", new TableInfo.Column("localMediaId", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSyncMetadata.put("localContentUri", new TableInfo.Column("localContentUri", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSyncMetadata.put("oneDriveItemId", new TableInfo.Column("oneDriveItemId", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSyncMetadata.put("localModifiedAt", new TableInfo.Column("localModifiedAt", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSyncMetadata.put("remoteModifiedAt", new TableInfo.Column("remoteModifiedAt", "INTEGER", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSyncMetadata.put("cloudState", new TableInfo.Column("cloudState", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSyncMetadata.put("cachedFilePath", new TableInfo.Column("cachedFilePath", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSyncMetadata.put("pendingDeleteTarget", new TableInfo.Column("pendingDeleteTarget", "TEXT", true, 0, null, TableInfo.CREATED_FROM_ENTITY));
        _columnsSyncMetadata.put("lastError", new TableInfo.Column("lastError", "TEXT", false, 0, null, TableInfo.CREATED_FROM_ENTITY));
        final HashSet<TableInfo.ForeignKey> _foreignKeysSyncMetadata = new HashSet<TableInfo.ForeignKey>(0);
        final HashSet<TableInfo.Index> _indicesSyncMetadata = new HashSet<TableInfo.Index>(0);
        final TableInfo _infoSyncMetadata = new TableInfo("sync_metadata", _columnsSyncMetadata, _foreignKeysSyncMetadata, _indicesSyncMetadata);
        final TableInfo _existingSyncMetadata = TableInfo.read(db, "sync_metadata");
        if (!_infoSyncMetadata.equals(_existingSyncMetadata)) {
          return new RoomOpenHelper.ValidationResult(false, "sync_metadata(com.onegallery.sync.data.local.db.SyncMetadataEntity).\n"
                  + " Expected:\n" + _infoSyncMetadata + "\n"
                  + " Found:\n" + _existingSyncMetadata);
        }
        return new RoomOpenHelper.ValidationResult(true, null);
      }
    }, "033cbf7bc7d5574391b642d32d07917f", "f4cfc41e209c8afaa3cbb6ba63c7575e");
    final SupportSQLiteOpenHelper.Configuration _sqliteConfig = SupportSQLiteOpenHelper.Configuration.builder(config.context).name(config.name).callback(_openCallback).build();
    final SupportSQLiteOpenHelper _helper = config.sqliteOpenHelperFactory.create(_sqliteConfig);
    return _helper;
  }

  @Override
  @NonNull
  protected InvalidationTracker createInvalidationTracker() {
    final HashMap<String, String> _shadowTablesMap = new HashMap<String, String>(0);
    final HashMap<String, Set<String>> _viewTables = new HashMap<String, Set<String>>(0);
    return new InvalidationTracker(this, _shadowTablesMap, _viewTables, "sync_metadata");
  }

  @Override
  public void clearAllTables() {
    super.assertNotMainThread();
    final SupportSQLiteDatabase _db = super.getOpenHelper().getWritableDatabase();
    try {
      super.beginTransaction();
      _db.execSQL("DELETE FROM `sync_metadata`");
      super.setTransactionSuccessful();
    } finally {
      super.endTransaction();
      _db.query("PRAGMA wal_checkpoint(FULL)").close();
      if (!_db.inTransaction()) {
        _db.execSQL("VACUUM");
      }
    }
  }

  @Override
  @NonNull
  protected Map<Class<?>, List<Class<?>>> getRequiredTypeConverters() {
    final HashMap<Class<?>, List<Class<?>>> _typeConvertersMap = new HashMap<Class<?>, List<Class<?>>>();
    _typeConvertersMap.put(SyncMetadataDao.class, SyncMetadataDao_Impl.getRequiredConverters());
    return _typeConvertersMap;
  }

  @Override
  @NonNull
  public Set<Class<? extends AutoMigrationSpec>> getRequiredAutoMigrationSpecs() {
    final HashSet<Class<? extends AutoMigrationSpec>> _autoMigrationSpecsSet = new HashSet<Class<? extends AutoMigrationSpec>>();
    return _autoMigrationSpecsSet;
  }

  @Override
  @NonNull
  public List<Migration> getAutoMigrations(
      @NonNull final Map<Class<? extends AutoMigrationSpec>, AutoMigrationSpec> autoMigrationSpecs) {
    final List<Migration> _autoMigrations = new ArrayList<Migration>();
    return _autoMigrations;
  }

  @Override
  public SyncMetadataDao syncMetadataDao() {
    if (_syncMetadataDao != null) {
      return _syncMetadataDao;
    } else {
      synchronized(this) {
        if(_syncMetadataDao == null) {
          _syncMetadataDao = new SyncMetadataDao_Impl(this);
        }
        return _syncMetadataDao;
      }
    }
  }
}
