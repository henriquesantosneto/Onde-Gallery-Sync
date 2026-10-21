# Referência de configuração e constantes

Valores implementados no código (podem mudar entre versões; conferir fonte se divergir).

## Versão do app

Em `app/build.gradle.kts`: `versionName`, `versionCode` (ex.: 0.8.211 / 263).

## SyncCoordinator

| Constante | Valor | Descrição |
|-----------|-------|-----------|
| `GALLERY_METADATA_DEBOUNCE_MS` | 450 | Debounce do Flow Room → UI |
| `GALLERY_DISPLAY_REFRESH_COALESCE_MS` | 5000 | Refresh galeria durante prefetch bulk |
| `ALBUM_COVER_REVISION_COALESCE_MS` | 280 | Agrupar revisões de capa |
| `ACTIVE_ITEM_PROGRESS_MIN_INTERVAL_MS` | 280 | Throttle barras de transferência |
| `PARALLEL_UPLOADS` | 3 | Uploads Graph simultâneos |
| `PARALLEL_THUMBNAILS` | 3 | Miniaturas simultâneas |
| `ROOM_UPSERT_CHUNK` | 64 | Upsert em lote Room |
| `THUMB_CACHE_MAX_SIDE_PX` | 1280 | Lado máx. miniatura local |
| `THUMB_CACHE_JPEG_QUALITY` | 88 | Qualidade JPEG thumb |
| `THUMB_PREFETCH_BATCH` | 512 | Itens por leva pós-sync |
| `THUMB_PREFETCH_EXTRA_ROUNDS` | 8 | Rodadas extras de segurança |
| `THUMB_PREFETCH_MAX_ROUNDS` | 36 | Teto de levas por sync |
| `THUMB_PREFETCH_MAX_TOTAL_PER_SYNC` | 2500 | Teto de thumbs por sync |
| `GPS_BACKFILL_ROUNDS` | 8 | Passagens GPS |
| `GPS_LOCAL_BATCH` / `GPS_CLOUD_BATCH` | 500 | Probes por passagem |
| `PARALLEL_GPS_BACKFILL` | 3 | Análises EXIF/GPS em paralelo por passagem |
| `LOCAL_ACCESS_WRITE_THROTTLE_MS` | 60000 | Throttle gravações MediaStore |

Tolerância de timestamp na reconciliação: **2 s** (privado no coordinator).

## SyncPreferences

| Constante | Valor | Descrição |
|-----------|-------|-----------|
| `CANCEL_COOLDOWN_MS` | 10 min | Após cancelar sync manual |
| `AUTO_BIDIRECTIONAL_MIN_INTERVAL_MS` | 12 min | Entre syncs automáticos |
| `MEDIA_LIBRARY_SUPPRESS_DURING_SYNC_MS` | 15 min | Watcher off durante sync |
| `MEDIA_LIBRARY_SUPPRESS_AFTER_LOCAL_RELEASE_MS` | 10 min | Watcher off após release |
| `DEFAULT_LOCAL_IDLE_RELEASE_DAYS` | 90 | Padrão auto-release (1–180) |

Chaves booleanas comuns: `wifiOnly`, `backgroundSyncAlwaysEnabled`, `hasCompletedSetup`, `autoFreeLocalSpaceWhenSynced`, pausa automática.

## Background

| Origem | Valor |
|--------|-------|
| WorkManager periodic | 15 min |
| `enqueueSyncSoon` default delay | 30 s |
| Rede (Application) | ~25 s |
| Boot | 90 s |
| MediaStore debounce | 8 s |
| MediaStore → sync delay | 45 s |

## OneDriveRepository / Graph

| Constante | Valor |
|-----------|-------|
| `FULL_REMOTE_LISTING_INTERVAL_MS` | 24 h |
| `PARENT_WALK_MAX_DEPTH` | 48 |
| `EXIF_PROBE_BYTES` | 512 KiB |
| `STREAM_BUFFER_BYTES` | 64 KiB |
| OkHttp connect / read / call | 30 s / 90 s / 120 s |

## GraphResumableTransfer

| Constante | Valor |
|-----------|-------|
| `SIMPLE_UPLOAD_MAX_BYTES` | 4 MiB |

## Room UI

| Limite | Valor |
|--------|-------|
| Fila pendente observada | LIMIT **200** |

## Outros

| Componente | Constante |
|------------|-----------|
| VideoThumbnailHelper | MAX_EDGE 512 px |
| MapMarkerBitmapCache | MAX_ENTRIES 512 |

## Capacidade

`SyncCapability.bidirectionalSyncEnabled = true`
