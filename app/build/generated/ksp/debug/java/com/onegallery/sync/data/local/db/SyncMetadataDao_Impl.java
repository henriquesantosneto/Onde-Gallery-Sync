package com.onegallery.sync.data.local.db;

import android.database.Cursor;
import android.os.CancellationSignal;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.CoroutinesRoom;
import androidx.room.EntityInsertionAdapter;
import androidx.room.RoomDatabase;
import androidx.room.RoomSQLiteQuery;
import androidx.room.SharedSQLiteStatement;
import androidx.room.util.CursorUtil;
import androidx.room.util.DBUtil;
import androidx.sqlite.db.SupportSQLiteStatement;
import com.onegallery.sync.sync.CloudSyncState;
import com.onegallery.sync.sync.PendingDeleteTarget;
import java.lang.Class;
import java.lang.Exception;
import java.lang.Integer;
import java.lang.Long;
import java.lang.Object;
import java.lang.Override;
import java.lang.String;
import java.lang.SuppressWarnings;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import javax.annotation.processing.Generated;
import kotlin.Unit;
import kotlin.coroutines.Continuation;
import kotlinx.coroutines.flow.Flow;

@Generated("androidx.room.RoomProcessor")
@SuppressWarnings({"unchecked", "deprecation"})
public final class SyncMetadataDao_Impl implements SyncMetadataDao {
  private final RoomDatabase __db;

  private final EntityInsertionAdapter<SyncMetadataEntity> __insertionAdapterOfSyncMetadataEntity;

  private final SyncEnumConverters __syncEnumConverters = new SyncEnumConverters();

  private final SharedSQLiteStatement __preparedStmtOfDeleteByCanonicalPath;

  private final SharedSQLiteStatement __preparedStmtOfUpdatePendingDelete;

  public SyncMetadataDao_Impl(@NonNull final RoomDatabase __db) {
    this.__db = __db;
    this.__insertionAdapterOfSyncMetadataEntity = new EntityInsertionAdapter<SyncMetadataEntity>(__db) {
      @Override
      @NonNull
      protected String createQuery() {
        return "INSERT OR REPLACE INTO `sync_metadata` (`canonicalPath`,`displayName`,`albumName`,`localMediaId`,`localContentUri`,`oneDriveItemId`,`localModifiedAt`,`remoteModifiedAt`,`cloudState`,`cachedFilePath`,`pendingDeleteTarget`,`lastError`) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)";
      }

      @Override
      protected void bind(@NonNull final SupportSQLiteStatement statement,
          @NonNull final SyncMetadataEntity entity) {
        statement.bindString(1, entity.getCanonicalPath());
        statement.bindString(2, entity.getDisplayName());
        statement.bindString(3, entity.getAlbumName());
        if (entity.getLocalMediaId() == null) {
          statement.bindNull(4);
        } else {
          statement.bindLong(4, entity.getLocalMediaId());
        }
        if (entity.getLocalContentUri() == null) {
          statement.bindNull(5);
        } else {
          statement.bindString(5, entity.getLocalContentUri());
        }
        if (entity.getOneDriveItemId() == null) {
          statement.bindNull(6);
        } else {
          statement.bindString(6, entity.getOneDriveItemId());
        }
        if (entity.getLocalModifiedAt() == null) {
          statement.bindNull(7);
        } else {
          statement.bindLong(7, entity.getLocalModifiedAt());
        }
        if (entity.getRemoteModifiedAt() == null) {
          statement.bindNull(8);
        } else {
          statement.bindLong(8, entity.getRemoteModifiedAt());
        }
        final String _tmp = __syncEnumConverters.fromCloudState(entity.getCloudState());
        statement.bindString(9, _tmp);
        if (entity.getCachedFilePath() == null) {
          statement.bindNull(10);
        } else {
          statement.bindString(10, entity.getCachedFilePath());
        }
        final String _tmp_1 = __syncEnumConverters.fromPendingDelete(entity.getPendingDeleteTarget());
        statement.bindString(11, _tmp_1);
        if (entity.getLastError() == null) {
          statement.bindNull(12);
        } else {
          statement.bindString(12, entity.getLastError());
        }
      }
    };
    this.__preparedStmtOfDeleteByCanonicalPath = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "DELETE FROM sync_metadata WHERE canonicalPath = ?";
        return _query;
      }
    };
    this.__preparedStmtOfUpdatePendingDelete = new SharedSQLiteStatement(__db) {
      @Override
      @NonNull
      public String createQuery() {
        final String _query = "UPDATE sync_metadata SET pendingDeleteTarget = ? WHERE canonicalPath = ?";
        return _query;
      }
    };
  }

  @Override
  public Object upsert(final SyncMetadataEntity entity,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        __db.beginTransaction();
        try {
          __insertionAdapterOfSyncMetadataEntity.insert(entity);
          __db.setTransactionSuccessful();
          return Unit.INSTANCE;
        } finally {
          __db.endTransaction();
        }
      }
    }, $completion);
  }

  @Override
  public Object deleteByCanonicalPath(final String canonicalPath,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfDeleteByCanonicalPath.acquire();
        int _argIndex = 1;
        _stmt.bindString(_argIndex, canonicalPath);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfDeleteByCanonicalPath.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Object updatePendingDelete(final String canonicalPath, final PendingDeleteTarget target,
      final Continuation<? super Unit> $completion) {
    return CoroutinesRoom.execute(__db, true, new Callable<Unit>() {
      @Override
      @NonNull
      public Unit call() throws Exception {
        final SupportSQLiteStatement _stmt = __preparedStmtOfUpdatePendingDelete.acquire();
        int _argIndex = 1;
        final String _tmp = __syncEnumConverters.fromPendingDelete(target);
        _stmt.bindString(_argIndex, _tmp);
        _argIndex = 2;
        _stmt.bindString(_argIndex, canonicalPath);
        try {
          __db.beginTransaction();
          try {
            _stmt.executeUpdateDelete();
            __db.setTransactionSuccessful();
            return Unit.INSTANCE;
          } finally {
            __db.endTransaction();
          }
        } finally {
          __preparedStmtOfUpdatePendingDelete.release(_stmt);
        }
      }
    }, $completion);
  }

  @Override
  public Flow<List<SyncMetadataEntity>> observeAll() {
    final String _sql = "SELECT * FROM sync_metadata ORDER BY COALESCE(localModifiedAt, remoteModifiedAt) DESC";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"sync_metadata"}, new Callable<List<SyncMetadataEntity>>() {
      @Override
      @NonNull
      public List<SyncMetadataEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfCanonicalPath = CursorUtil.getColumnIndexOrThrow(_cursor, "canonicalPath");
          final int _cursorIndexOfDisplayName = CursorUtil.getColumnIndexOrThrow(_cursor, "displayName");
          final int _cursorIndexOfAlbumName = CursorUtil.getColumnIndexOrThrow(_cursor, "albumName");
          final int _cursorIndexOfLocalMediaId = CursorUtil.getColumnIndexOrThrow(_cursor, "localMediaId");
          final int _cursorIndexOfLocalContentUri = CursorUtil.getColumnIndexOrThrow(_cursor, "localContentUri");
          final int _cursorIndexOfOneDriveItemId = CursorUtil.getColumnIndexOrThrow(_cursor, "oneDriveItemId");
          final int _cursorIndexOfLocalModifiedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "localModifiedAt");
          final int _cursorIndexOfRemoteModifiedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "remoteModifiedAt");
          final int _cursorIndexOfCloudState = CursorUtil.getColumnIndexOrThrow(_cursor, "cloudState");
          final int _cursorIndexOfCachedFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "cachedFilePath");
          final int _cursorIndexOfPendingDeleteTarget = CursorUtil.getColumnIndexOrThrow(_cursor, "pendingDeleteTarget");
          final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "lastError");
          final List<SyncMetadataEntity> _result = new ArrayList<SyncMetadataEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final SyncMetadataEntity _item;
            final String _tmpCanonicalPath;
            _tmpCanonicalPath = _cursor.getString(_cursorIndexOfCanonicalPath);
            final String _tmpDisplayName;
            _tmpDisplayName = _cursor.getString(_cursorIndexOfDisplayName);
            final String _tmpAlbumName;
            _tmpAlbumName = _cursor.getString(_cursorIndexOfAlbumName);
            final Long _tmpLocalMediaId;
            if (_cursor.isNull(_cursorIndexOfLocalMediaId)) {
              _tmpLocalMediaId = null;
            } else {
              _tmpLocalMediaId = _cursor.getLong(_cursorIndexOfLocalMediaId);
            }
            final String _tmpLocalContentUri;
            if (_cursor.isNull(_cursorIndexOfLocalContentUri)) {
              _tmpLocalContentUri = null;
            } else {
              _tmpLocalContentUri = _cursor.getString(_cursorIndexOfLocalContentUri);
            }
            final String _tmpOneDriveItemId;
            if (_cursor.isNull(_cursorIndexOfOneDriveItemId)) {
              _tmpOneDriveItemId = null;
            } else {
              _tmpOneDriveItemId = _cursor.getString(_cursorIndexOfOneDriveItemId);
            }
            final Long _tmpLocalModifiedAt;
            if (_cursor.isNull(_cursorIndexOfLocalModifiedAt)) {
              _tmpLocalModifiedAt = null;
            } else {
              _tmpLocalModifiedAt = _cursor.getLong(_cursorIndexOfLocalModifiedAt);
            }
            final Long _tmpRemoteModifiedAt;
            if (_cursor.isNull(_cursorIndexOfRemoteModifiedAt)) {
              _tmpRemoteModifiedAt = null;
            } else {
              _tmpRemoteModifiedAt = _cursor.getLong(_cursorIndexOfRemoteModifiedAt);
            }
            final CloudSyncState _tmpCloudState;
            final String _tmp;
            _tmp = _cursor.getString(_cursorIndexOfCloudState);
            _tmpCloudState = __syncEnumConverters.toCloudState(_tmp);
            final String _tmpCachedFilePath;
            if (_cursor.isNull(_cursorIndexOfCachedFilePath)) {
              _tmpCachedFilePath = null;
            } else {
              _tmpCachedFilePath = _cursor.getString(_cursorIndexOfCachedFilePath);
            }
            final PendingDeleteTarget _tmpPendingDeleteTarget;
            final String _tmp_1;
            _tmp_1 = _cursor.getString(_cursorIndexOfPendingDeleteTarget);
            _tmpPendingDeleteTarget = __syncEnumConverters.toPendingDelete(_tmp_1);
            final String _tmpLastError;
            if (_cursor.isNull(_cursorIndexOfLastError)) {
              _tmpLastError = null;
            } else {
              _tmpLastError = _cursor.getString(_cursorIndexOfLastError);
            }
            _item = new SyncMetadataEntity(_tmpCanonicalPath,_tmpDisplayName,_tmpAlbumName,_tmpLocalMediaId,_tmpLocalContentUri,_tmpOneDriveItemId,_tmpLocalModifiedAt,_tmpRemoteModifiedAt,_tmpCloudState,_tmpCachedFilePath,_tmpPendingDeleteTarget,_tmpLastError);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Object getAll(final Continuation<? super List<SyncMetadataEntity>> $completion) {
    final String _sql = "SELECT * FROM sync_metadata";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<List<SyncMetadataEntity>>() {
      @Override
      @NonNull
      public List<SyncMetadataEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfCanonicalPath = CursorUtil.getColumnIndexOrThrow(_cursor, "canonicalPath");
          final int _cursorIndexOfDisplayName = CursorUtil.getColumnIndexOrThrow(_cursor, "displayName");
          final int _cursorIndexOfAlbumName = CursorUtil.getColumnIndexOrThrow(_cursor, "albumName");
          final int _cursorIndexOfLocalMediaId = CursorUtil.getColumnIndexOrThrow(_cursor, "localMediaId");
          final int _cursorIndexOfLocalContentUri = CursorUtil.getColumnIndexOrThrow(_cursor, "localContentUri");
          final int _cursorIndexOfOneDriveItemId = CursorUtil.getColumnIndexOrThrow(_cursor, "oneDriveItemId");
          final int _cursorIndexOfLocalModifiedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "localModifiedAt");
          final int _cursorIndexOfRemoteModifiedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "remoteModifiedAt");
          final int _cursorIndexOfCloudState = CursorUtil.getColumnIndexOrThrow(_cursor, "cloudState");
          final int _cursorIndexOfCachedFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "cachedFilePath");
          final int _cursorIndexOfPendingDeleteTarget = CursorUtil.getColumnIndexOrThrow(_cursor, "pendingDeleteTarget");
          final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "lastError");
          final List<SyncMetadataEntity> _result = new ArrayList<SyncMetadataEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final SyncMetadataEntity _item;
            final String _tmpCanonicalPath;
            _tmpCanonicalPath = _cursor.getString(_cursorIndexOfCanonicalPath);
            final String _tmpDisplayName;
            _tmpDisplayName = _cursor.getString(_cursorIndexOfDisplayName);
            final String _tmpAlbumName;
            _tmpAlbumName = _cursor.getString(_cursorIndexOfAlbumName);
            final Long _tmpLocalMediaId;
            if (_cursor.isNull(_cursorIndexOfLocalMediaId)) {
              _tmpLocalMediaId = null;
            } else {
              _tmpLocalMediaId = _cursor.getLong(_cursorIndexOfLocalMediaId);
            }
            final String _tmpLocalContentUri;
            if (_cursor.isNull(_cursorIndexOfLocalContentUri)) {
              _tmpLocalContentUri = null;
            } else {
              _tmpLocalContentUri = _cursor.getString(_cursorIndexOfLocalContentUri);
            }
            final String _tmpOneDriveItemId;
            if (_cursor.isNull(_cursorIndexOfOneDriveItemId)) {
              _tmpOneDriveItemId = null;
            } else {
              _tmpOneDriveItemId = _cursor.getString(_cursorIndexOfOneDriveItemId);
            }
            final Long _tmpLocalModifiedAt;
            if (_cursor.isNull(_cursorIndexOfLocalModifiedAt)) {
              _tmpLocalModifiedAt = null;
            } else {
              _tmpLocalModifiedAt = _cursor.getLong(_cursorIndexOfLocalModifiedAt);
            }
            final Long _tmpRemoteModifiedAt;
            if (_cursor.isNull(_cursorIndexOfRemoteModifiedAt)) {
              _tmpRemoteModifiedAt = null;
            } else {
              _tmpRemoteModifiedAt = _cursor.getLong(_cursorIndexOfRemoteModifiedAt);
            }
            final CloudSyncState _tmpCloudState;
            final String _tmp;
            _tmp = _cursor.getString(_cursorIndexOfCloudState);
            _tmpCloudState = __syncEnumConverters.toCloudState(_tmp);
            final String _tmpCachedFilePath;
            if (_cursor.isNull(_cursorIndexOfCachedFilePath)) {
              _tmpCachedFilePath = null;
            } else {
              _tmpCachedFilePath = _cursor.getString(_cursorIndexOfCachedFilePath);
            }
            final PendingDeleteTarget _tmpPendingDeleteTarget;
            final String _tmp_1;
            _tmp_1 = _cursor.getString(_cursorIndexOfPendingDeleteTarget);
            _tmpPendingDeleteTarget = __syncEnumConverters.toPendingDelete(_tmp_1);
            final String _tmpLastError;
            if (_cursor.isNull(_cursorIndexOfLastError)) {
              _tmpLastError = null;
            } else {
              _tmpLastError = _cursor.getString(_cursorIndexOfLastError);
            }
            _item = new SyncMetadataEntity(_tmpCanonicalPath,_tmpDisplayName,_tmpAlbumName,_tmpLocalMediaId,_tmpLocalContentUri,_tmpOneDriveItemId,_tmpLocalModifiedAt,_tmpRemoteModifiedAt,_tmpCloudState,_tmpCachedFilePath,_tmpPendingDeleteTarget,_tmpLastError);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getByCanonicalPath(final String canonicalPath,
      final Continuation<? super SyncMetadataEntity> $completion) {
    final String _sql = "SELECT * FROM sync_metadata WHERE canonicalPath = ? LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, canonicalPath);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<SyncMetadataEntity>() {
      @Override
      @Nullable
      public SyncMetadataEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfCanonicalPath = CursorUtil.getColumnIndexOrThrow(_cursor, "canonicalPath");
          final int _cursorIndexOfDisplayName = CursorUtil.getColumnIndexOrThrow(_cursor, "displayName");
          final int _cursorIndexOfAlbumName = CursorUtil.getColumnIndexOrThrow(_cursor, "albumName");
          final int _cursorIndexOfLocalMediaId = CursorUtil.getColumnIndexOrThrow(_cursor, "localMediaId");
          final int _cursorIndexOfLocalContentUri = CursorUtil.getColumnIndexOrThrow(_cursor, "localContentUri");
          final int _cursorIndexOfOneDriveItemId = CursorUtil.getColumnIndexOrThrow(_cursor, "oneDriveItemId");
          final int _cursorIndexOfLocalModifiedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "localModifiedAt");
          final int _cursorIndexOfRemoteModifiedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "remoteModifiedAt");
          final int _cursorIndexOfCloudState = CursorUtil.getColumnIndexOrThrow(_cursor, "cloudState");
          final int _cursorIndexOfCachedFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "cachedFilePath");
          final int _cursorIndexOfPendingDeleteTarget = CursorUtil.getColumnIndexOrThrow(_cursor, "pendingDeleteTarget");
          final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "lastError");
          final SyncMetadataEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpCanonicalPath;
            _tmpCanonicalPath = _cursor.getString(_cursorIndexOfCanonicalPath);
            final String _tmpDisplayName;
            _tmpDisplayName = _cursor.getString(_cursorIndexOfDisplayName);
            final String _tmpAlbumName;
            _tmpAlbumName = _cursor.getString(_cursorIndexOfAlbumName);
            final Long _tmpLocalMediaId;
            if (_cursor.isNull(_cursorIndexOfLocalMediaId)) {
              _tmpLocalMediaId = null;
            } else {
              _tmpLocalMediaId = _cursor.getLong(_cursorIndexOfLocalMediaId);
            }
            final String _tmpLocalContentUri;
            if (_cursor.isNull(_cursorIndexOfLocalContentUri)) {
              _tmpLocalContentUri = null;
            } else {
              _tmpLocalContentUri = _cursor.getString(_cursorIndexOfLocalContentUri);
            }
            final String _tmpOneDriveItemId;
            if (_cursor.isNull(_cursorIndexOfOneDriveItemId)) {
              _tmpOneDriveItemId = null;
            } else {
              _tmpOneDriveItemId = _cursor.getString(_cursorIndexOfOneDriveItemId);
            }
            final Long _tmpLocalModifiedAt;
            if (_cursor.isNull(_cursorIndexOfLocalModifiedAt)) {
              _tmpLocalModifiedAt = null;
            } else {
              _tmpLocalModifiedAt = _cursor.getLong(_cursorIndexOfLocalModifiedAt);
            }
            final Long _tmpRemoteModifiedAt;
            if (_cursor.isNull(_cursorIndexOfRemoteModifiedAt)) {
              _tmpRemoteModifiedAt = null;
            } else {
              _tmpRemoteModifiedAt = _cursor.getLong(_cursorIndexOfRemoteModifiedAt);
            }
            final CloudSyncState _tmpCloudState;
            final String _tmp;
            _tmp = _cursor.getString(_cursorIndexOfCloudState);
            _tmpCloudState = __syncEnumConverters.toCloudState(_tmp);
            final String _tmpCachedFilePath;
            if (_cursor.isNull(_cursorIndexOfCachedFilePath)) {
              _tmpCachedFilePath = null;
            } else {
              _tmpCachedFilePath = _cursor.getString(_cursorIndexOfCachedFilePath);
            }
            final PendingDeleteTarget _tmpPendingDeleteTarget;
            final String _tmp_1;
            _tmp_1 = _cursor.getString(_cursorIndexOfPendingDeleteTarget);
            _tmpPendingDeleteTarget = __syncEnumConverters.toPendingDelete(_tmp_1);
            final String _tmpLastError;
            if (_cursor.isNull(_cursorIndexOfLastError)) {
              _tmpLastError = null;
            } else {
              _tmpLastError = _cursor.getString(_cursorIndexOfLastError);
            }
            _result = new SyncMetadataEntity(_tmpCanonicalPath,_tmpDisplayName,_tmpAlbumName,_tmpLocalMediaId,_tmpLocalContentUri,_tmpOneDriveItemId,_tmpLocalModifiedAt,_tmpRemoteModifiedAt,_tmpCloudState,_tmpCachedFilePath,_tmpPendingDeleteTarget,_tmpLastError);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getByLocalId(final long localMediaId,
      final Continuation<? super SyncMetadataEntity> $completion) {
    final String _sql = "SELECT * FROM sync_metadata WHERE localMediaId = ? LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindLong(_argIndex, localMediaId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<SyncMetadataEntity>() {
      @Override
      @Nullable
      public SyncMetadataEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfCanonicalPath = CursorUtil.getColumnIndexOrThrow(_cursor, "canonicalPath");
          final int _cursorIndexOfDisplayName = CursorUtil.getColumnIndexOrThrow(_cursor, "displayName");
          final int _cursorIndexOfAlbumName = CursorUtil.getColumnIndexOrThrow(_cursor, "albumName");
          final int _cursorIndexOfLocalMediaId = CursorUtil.getColumnIndexOrThrow(_cursor, "localMediaId");
          final int _cursorIndexOfLocalContentUri = CursorUtil.getColumnIndexOrThrow(_cursor, "localContentUri");
          final int _cursorIndexOfOneDriveItemId = CursorUtil.getColumnIndexOrThrow(_cursor, "oneDriveItemId");
          final int _cursorIndexOfLocalModifiedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "localModifiedAt");
          final int _cursorIndexOfRemoteModifiedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "remoteModifiedAt");
          final int _cursorIndexOfCloudState = CursorUtil.getColumnIndexOrThrow(_cursor, "cloudState");
          final int _cursorIndexOfCachedFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "cachedFilePath");
          final int _cursorIndexOfPendingDeleteTarget = CursorUtil.getColumnIndexOrThrow(_cursor, "pendingDeleteTarget");
          final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "lastError");
          final SyncMetadataEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpCanonicalPath;
            _tmpCanonicalPath = _cursor.getString(_cursorIndexOfCanonicalPath);
            final String _tmpDisplayName;
            _tmpDisplayName = _cursor.getString(_cursorIndexOfDisplayName);
            final String _tmpAlbumName;
            _tmpAlbumName = _cursor.getString(_cursorIndexOfAlbumName);
            final Long _tmpLocalMediaId;
            if (_cursor.isNull(_cursorIndexOfLocalMediaId)) {
              _tmpLocalMediaId = null;
            } else {
              _tmpLocalMediaId = _cursor.getLong(_cursorIndexOfLocalMediaId);
            }
            final String _tmpLocalContentUri;
            if (_cursor.isNull(_cursorIndexOfLocalContentUri)) {
              _tmpLocalContentUri = null;
            } else {
              _tmpLocalContentUri = _cursor.getString(_cursorIndexOfLocalContentUri);
            }
            final String _tmpOneDriveItemId;
            if (_cursor.isNull(_cursorIndexOfOneDriveItemId)) {
              _tmpOneDriveItemId = null;
            } else {
              _tmpOneDriveItemId = _cursor.getString(_cursorIndexOfOneDriveItemId);
            }
            final Long _tmpLocalModifiedAt;
            if (_cursor.isNull(_cursorIndexOfLocalModifiedAt)) {
              _tmpLocalModifiedAt = null;
            } else {
              _tmpLocalModifiedAt = _cursor.getLong(_cursorIndexOfLocalModifiedAt);
            }
            final Long _tmpRemoteModifiedAt;
            if (_cursor.isNull(_cursorIndexOfRemoteModifiedAt)) {
              _tmpRemoteModifiedAt = null;
            } else {
              _tmpRemoteModifiedAt = _cursor.getLong(_cursorIndexOfRemoteModifiedAt);
            }
            final CloudSyncState _tmpCloudState;
            final String _tmp;
            _tmp = _cursor.getString(_cursorIndexOfCloudState);
            _tmpCloudState = __syncEnumConverters.toCloudState(_tmp);
            final String _tmpCachedFilePath;
            if (_cursor.isNull(_cursorIndexOfCachedFilePath)) {
              _tmpCachedFilePath = null;
            } else {
              _tmpCachedFilePath = _cursor.getString(_cursorIndexOfCachedFilePath);
            }
            final PendingDeleteTarget _tmpPendingDeleteTarget;
            final String _tmp_1;
            _tmp_1 = _cursor.getString(_cursorIndexOfPendingDeleteTarget);
            _tmpPendingDeleteTarget = __syncEnumConverters.toPendingDelete(_tmp_1);
            final String _tmpLastError;
            if (_cursor.isNull(_cursorIndexOfLastError)) {
              _tmpLastError = null;
            } else {
              _tmpLastError = _cursor.getString(_cursorIndexOfLastError);
            }
            _result = new SyncMetadataEntity(_tmpCanonicalPath,_tmpDisplayName,_tmpAlbumName,_tmpLocalMediaId,_tmpLocalContentUri,_tmpOneDriveItemId,_tmpLocalModifiedAt,_tmpRemoteModifiedAt,_tmpCloudState,_tmpCachedFilePath,_tmpPendingDeleteTarget,_tmpLastError);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Object getByOneDriveId(final String itemId,
      final Continuation<? super SyncMetadataEntity> $completion) {
    final String _sql = "SELECT * FROM sync_metadata WHERE oneDriveItemId = ? LIMIT 1";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 1);
    int _argIndex = 1;
    _statement.bindString(_argIndex, itemId);
    final CancellationSignal _cancellationSignal = DBUtil.createCancellationSignal();
    return CoroutinesRoom.execute(__db, false, _cancellationSignal, new Callable<SyncMetadataEntity>() {
      @Override
      @Nullable
      public SyncMetadataEntity call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfCanonicalPath = CursorUtil.getColumnIndexOrThrow(_cursor, "canonicalPath");
          final int _cursorIndexOfDisplayName = CursorUtil.getColumnIndexOrThrow(_cursor, "displayName");
          final int _cursorIndexOfAlbumName = CursorUtil.getColumnIndexOrThrow(_cursor, "albumName");
          final int _cursorIndexOfLocalMediaId = CursorUtil.getColumnIndexOrThrow(_cursor, "localMediaId");
          final int _cursorIndexOfLocalContentUri = CursorUtil.getColumnIndexOrThrow(_cursor, "localContentUri");
          final int _cursorIndexOfOneDriveItemId = CursorUtil.getColumnIndexOrThrow(_cursor, "oneDriveItemId");
          final int _cursorIndexOfLocalModifiedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "localModifiedAt");
          final int _cursorIndexOfRemoteModifiedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "remoteModifiedAt");
          final int _cursorIndexOfCloudState = CursorUtil.getColumnIndexOrThrow(_cursor, "cloudState");
          final int _cursorIndexOfCachedFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "cachedFilePath");
          final int _cursorIndexOfPendingDeleteTarget = CursorUtil.getColumnIndexOrThrow(_cursor, "pendingDeleteTarget");
          final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "lastError");
          final SyncMetadataEntity _result;
          if (_cursor.moveToFirst()) {
            final String _tmpCanonicalPath;
            _tmpCanonicalPath = _cursor.getString(_cursorIndexOfCanonicalPath);
            final String _tmpDisplayName;
            _tmpDisplayName = _cursor.getString(_cursorIndexOfDisplayName);
            final String _tmpAlbumName;
            _tmpAlbumName = _cursor.getString(_cursorIndexOfAlbumName);
            final Long _tmpLocalMediaId;
            if (_cursor.isNull(_cursorIndexOfLocalMediaId)) {
              _tmpLocalMediaId = null;
            } else {
              _tmpLocalMediaId = _cursor.getLong(_cursorIndexOfLocalMediaId);
            }
            final String _tmpLocalContentUri;
            if (_cursor.isNull(_cursorIndexOfLocalContentUri)) {
              _tmpLocalContentUri = null;
            } else {
              _tmpLocalContentUri = _cursor.getString(_cursorIndexOfLocalContentUri);
            }
            final String _tmpOneDriveItemId;
            if (_cursor.isNull(_cursorIndexOfOneDriveItemId)) {
              _tmpOneDriveItemId = null;
            } else {
              _tmpOneDriveItemId = _cursor.getString(_cursorIndexOfOneDriveItemId);
            }
            final Long _tmpLocalModifiedAt;
            if (_cursor.isNull(_cursorIndexOfLocalModifiedAt)) {
              _tmpLocalModifiedAt = null;
            } else {
              _tmpLocalModifiedAt = _cursor.getLong(_cursorIndexOfLocalModifiedAt);
            }
            final Long _tmpRemoteModifiedAt;
            if (_cursor.isNull(_cursorIndexOfRemoteModifiedAt)) {
              _tmpRemoteModifiedAt = null;
            } else {
              _tmpRemoteModifiedAt = _cursor.getLong(_cursorIndexOfRemoteModifiedAt);
            }
            final CloudSyncState _tmpCloudState;
            final String _tmp;
            _tmp = _cursor.getString(_cursorIndexOfCloudState);
            _tmpCloudState = __syncEnumConverters.toCloudState(_tmp);
            final String _tmpCachedFilePath;
            if (_cursor.isNull(_cursorIndexOfCachedFilePath)) {
              _tmpCachedFilePath = null;
            } else {
              _tmpCachedFilePath = _cursor.getString(_cursorIndexOfCachedFilePath);
            }
            final PendingDeleteTarget _tmpPendingDeleteTarget;
            final String _tmp_1;
            _tmp_1 = _cursor.getString(_cursorIndexOfPendingDeleteTarget);
            _tmpPendingDeleteTarget = __syncEnumConverters.toPendingDelete(_tmp_1);
            final String _tmpLastError;
            if (_cursor.isNull(_cursorIndexOfLastError)) {
              _tmpLastError = null;
            } else {
              _tmpLastError = _cursor.getString(_cursorIndexOfLastError);
            }
            _result = new SyncMetadataEntity(_tmpCanonicalPath,_tmpDisplayName,_tmpAlbumName,_tmpLocalMediaId,_tmpLocalContentUri,_tmpOneDriveItemId,_tmpLocalModifiedAt,_tmpRemoteModifiedAt,_tmpCloudState,_tmpCachedFilePath,_tmpPendingDeleteTarget,_tmpLastError);
          } else {
            _result = null;
          }
          return _result;
        } finally {
          _cursor.close();
          _statement.release();
        }
      }
    }, $completion);
  }

  @Override
  public Flow<Integer> observePendingCount() {
    final String _sql = "SELECT COUNT(*) FROM sync_metadata WHERE cloudState IN ('PENDING_UPLOAD', 'PENDING_DOWNLOAD') OR pendingDeleteTarget != 'NONE'";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"sync_metadata"}, new Callable<Integer>() {
      @Override
      @NonNull
      public Integer call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final Integer _result;
          if (_cursor.moveToFirst()) {
            final int _tmp;
            _tmp = _cursor.getInt(0);
            _result = _tmp;
          } else {
            _result = 0;
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @Override
  public Flow<List<SyncMetadataEntity>> observePendingDeletes() {
    final String _sql = "SELECT * FROM sync_metadata WHERE pendingDeleteTarget != 'NONE'";
    final RoomSQLiteQuery _statement = RoomSQLiteQuery.acquire(_sql, 0);
    return CoroutinesRoom.createFlow(__db, false, new String[] {"sync_metadata"}, new Callable<List<SyncMetadataEntity>>() {
      @Override
      @NonNull
      public List<SyncMetadataEntity> call() throws Exception {
        final Cursor _cursor = DBUtil.query(__db, _statement, false, null);
        try {
          final int _cursorIndexOfCanonicalPath = CursorUtil.getColumnIndexOrThrow(_cursor, "canonicalPath");
          final int _cursorIndexOfDisplayName = CursorUtil.getColumnIndexOrThrow(_cursor, "displayName");
          final int _cursorIndexOfAlbumName = CursorUtil.getColumnIndexOrThrow(_cursor, "albumName");
          final int _cursorIndexOfLocalMediaId = CursorUtil.getColumnIndexOrThrow(_cursor, "localMediaId");
          final int _cursorIndexOfLocalContentUri = CursorUtil.getColumnIndexOrThrow(_cursor, "localContentUri");
          final int _cursorIndexOfOneDriveItemId = CursorUtil.getColumnIndexOrThrow(_cursor, "oneDriveItemId");
          final int _cursorIndexOfLocalModifiedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "localModifiedAt");
          final int _cursorIndexOfRemoteModifiedAt = CursorUtil.getColumnIndexOrThrow(_cursor, "remoteModifiedAt");
          final int _cursorIndexOfCloudState = CursorUtil.getColumnIndexOrThrow(_cursor, "cloudState");
          final int _cursorIndexOfCachedFilePath = CursorUtil.getColumnIndexOrThrow(_cursor, "cachedFilePath");
          final int _cursorIndexOfPendingDeleteTarget = CursorUtil.getColumnIndexOrThrow(_cursor, "pendingDeleteTarget");
          final int _cursorIndexOfLastError = CursorUtil.getColumnIndexOrThrow(_cursor, "lastError");
          final List<SyncMetadataEntity> _result = new ArrayList<SyncMetadataEntity>(_cursor.getCount());
          while (_cursor.moveToNext()) {
            final SyncMetadataEntity _item;
            final String _tmpCanonicalPath;
            _tmpCanonicalPath = _cursor.getString(_cursorIndexOfCanonicalPath);
            final String _tmpDisplayName;
            _tmpDisplayName = _cursor.getString(_cursorIndexOfDisplayName);
            final String _tmpAlbumName;
            _tmpAlbumName = _cursor.getString(_cursorIndexOfAlbumName);
            final Long _tmpLocalMediaId;
            if (_cursor.isNull(_cursorIndexOfLocalMediaId)) {
              _tmpLocalMediaId = null;
            } else {
              _tmpLocalMediaId = _cursor.getLong(_cursorIndexOfLocalMediaId);
            }
            final String _tmpLocalContentUri;
            if (_cursor.isNull(_cursorIndexOfLocalContentUri)) {
              _tmpLocalContentUri = null;
            } else {
              _tmpLocalContentUri = _cursor.getString(_cursorIndexOfLocalContentUri);
            }
            final String _tmpOneDriveItemId;
            if (_cursor.isNull(_cursorIndexOfOneDriveItemId)) {
              _tmpOneDriveItemId = null;
            } else {
              _tmpOneDriveItemId = _cursor.getString(_cursorIndexOfOneDriveItemId);
            }
            final Long _tmpLocalModifiedAt;
            if (_cursor.isNull(_cursorIndexOfLocalModifiedAt)) {
              _tmpLocalModifiedAt = null;
            } else {
              _tmpLocalModifiedAt = _cursor.getLong(_cursorIndexOfLocalModifiedAt);
            }
            final Long _tmpRemoteModifiedAt;
            if (_cursor.isNull(_cursorIndexOfRemoteModifiedAt)) {
              _tmpRemoteModifiedAt = null;
            } else {
              _tmpRemoteModifiedAt = _cursor.getLong(_cursorIndexOfRemoteModifiedAt);
            }
            final CloudSyncState _tmpCloudState;
            final String _tmp;
            _tmp = _cursor.getString(_cursorIndexOfCloudState);
            _tmpCloudState = __syncEnumConverters.toCloudState(_tmp);
            final String _tmpCachedFilePath;
            if (_cursor.isNull(_cursorIndexOfCachedFilePath)) {
              _tmpCachedFilePath = null;
            } else {
              _tmpCachedFilePath = _cursor.getString(_cursorIndexOfCachedFilePath);
            }
            final PendingDeleteTarget _tmpPendingDeleteTarget;
            final String _tmp_1;
            _tmp_1 = _cursor.getString(_cursorIndexOfPendingDeleteTarget);
            _tmpPendingDeleteTarget = __syncEnumConverters.toPendingDelete(_tmp_1);
            final String _tmpLastError;
            if (_cursor.isNull(_cursorIndexOfLastError)) {
              _tmpLastError = null;
            } else {
              _tmpLastError = _cursor.getString(_cursorIndexOfLastError);
            }
            _item = new SyncMetadataEntity(_tmpCanonicalPath,_tmpDisplayName,_tmpAlbumName,_tmpLocalMediaId,_tmpLocalContentUri,_tmpOneDriveItemId,_tmpLocalModifiedAt,_tmpRemoteModifiedAt,_tmpCloudState,_tmpCachedFilePath,_tmpPendingDeleteTarget,_tmpLastError);
            _result.add(_item);
          }
          return _result;
        } finally {
          _cursor.close();
        }
      }

      @Override
      protected void finalize() {
        _statement.release();
      }
    });
  }

  @NonNull
  public static List<Class<?>> getRequiredConverters() {
    return Collections.emptyList();
  }
}
