# Mapa de código — referência rápida para IA

Caminhos relativos à raiz do repo: `app/src/main/java/com/onegallery/sync/`.

## Entrada e DI

| Responsabilidade | Arquivo |
|------------------|---------|
| Application, wiring singletons | `OneGalleryApplication.kt` |
| Activity, permissões, delete launcher | `MainActivity.kt` |
| Navegação Compose, setup, tabs | `ui/OneGalleryApp.kt` |
| Lançar jobs sync da UI | `sync/SyncTaskLauncher.kt` |

## Sync (coração)

| Responsabilidade | Arquivo |
|------------------|---------|
| Orquestração completa | `sync/SyncCoordinator.kt` |
| Fases UI sync | `sync/SyncProgressPhase.kt` |
| Estado UI sync, fila, active items | `sync/SyncQueue.kt` (+ campos em coordinator) |
| Política lixeira, estados | `sync/SyncPolicy.kt`, `sync/CloudSyncState` (em SyncPolicy ou arquivo enum) |
| Caminhos canônicos | `sync/SyncPaths.kt` |
| Hash / fingerprint | `sync/ContentFingerprint.kt`, `sync/QuickXorHash.kt` |
| Preferências sync | `sync/SyncPreferences.kt` |
| Modo nuvem enum | `sync/SyncMode.kt` |
| Foreground service | `sync/SyncForegroundService.kt`, `sync/SyncForegroundGuard.kt` |
| Rede online/WiFi | `sync/NetworkMonitor.kt` |
| Boot | `sync/BootCompletedReceiver.kt` |
| Watcher MediaStore | `sync/MediaLibraryChangeWatcher.kt` |
| Saneamento índice | `sync/SyncIndexMaintenance.kt` |
| App em foreground | `sync/AppForegroundState.kt` |
| Exclusão pending enum | `sync/PendingDeleteTarget.kt` |

## Background

| Responsabilidade | Arquivo |
|------------------|---------|
| Worker | `worker/SyncWorker.kt` |
| Agendamento | `worker/BackgroundSyncScheduler.kt` |

## Dados locais

| Responsabilidade | Arquivo |
|------------------|---------|
| Room DB | `data/local/db/AppDatabase.kt` |
| Entidade índice | `data/local/db/SyncMetadataEntity.kt` |
| DAO metadata | `data/local/db/SyncMetadataDao.kt` |
| Álbuns usuário | `data/local/db/UserAlbumEntity.kt`, `UserAlbumDao.kt` |
| MediaStore scan | `data/local/LocalMediaRepository.kt` |
| Cache thumbs/originals | `data/local/MediaCacheRepository.kt` |
| GPS / label | `data/local/MediaLocationResolver.kt`, `MediaLocation.kt` |
| Data captura | `data/local/MediaCaptureTimeResolver.kt` |
| Thumb vídeo | `data/local/VideoThumbnailHelper.kt` |
| Delete Android 11+ | `data/local/MediaDeleteRequestRouter.kt` |
| Permissões | `data/local/MediaAccessPermissions.kt` |
| Lugares | `data/local/PlaceComponents.kt`, `PlaceMatchKey.kt` |

## Multicloud (Fase 1)

| Responsabilidade | Arquivo |
|------------------|---------|
| Contrato API nuvem | `data/cloud/CloudSyncBackend.kt` |
| Ficheiro remoto neutro | `data/cloud/RemoteCloudFile.kt` |
| Adapter OneDrive Graph | `data/cloud/OneDriveGraphCloudBackend.kt` |
| Registry (provedor ativo) | `data/cloud/CloudSyncBackendRegistry.kt` |
| Stub Dropbox | `data/cloud/DropboxCloudBackendStub.kt` |

Ver [MULTICLOUD.md](../docs/MULTICLOUD.md).

## OneDrive / Graph

| Responsabilidade | Arquivo |
|------------------|---------|
| Graph principal | `data/onedrive/OneDriveRepository.kt` |
| Retrofit API | `data/onedrive/GraphApiService.kt` |
| Upload/download resumável | `data/onedrive/GraphResumableTransfer.kt` |
| Prefs pasta Graph | `data/onedrive/GraphFolderPreferences.kt` |
| SAF | `data/onedrive/SaOneDriveRepository.kt`, `OneDriveFolderPreferences.kt` |
| Espelho Pictures | `data/onedrive/LocalMirrorRepository.kt` |
| Browser UI cloud | `ui/onedrive/OneDriveCloudBrowserScreen.kt` |
| Browser SAF | `ui/onedrive/OneDriveSafBrowserScreen.kt` |

## Auth

| Responsabilidade | Arquivo |
|------------------|---------|
| MSAL sessão | `auth/MsalAuthManager.kt` |
| Sign-in UI | `ui/auth/SignInGate.kt` |
| Config MSAL | `auth/MsalConfigFactory.kt`, `res/raw/auth_config.json` |

## UI galeria

| Responsabilidade | Arquivo |
|------------------|---------|
| ViewModel galeria | `ui/gallery/GalleryViewModel.kt` |
| Entity → item UI | `ui/gallery/GalleryMapper.kt` |
| Modelo item | `ui/gallery/GalleryDisplayItem.kt` |
| Visibilidade cloud | `ui/gallery/GalleryBrowseVisibility.kt` |
| Dedupe | `ui/gallery/GalleryDisplayDedupe.kt` |
| Grade | `ui/gallery/GalleryScreen.kt` |
| Célula thumb | `ui/gallery/GalleryThumbnailImage.kt` |
| Detalhe | `ui/gallery/MediaDetailScreen.kt` |
| Capas álbum | `ui/gallery/AlbumCoverModel.kt`, `AlbumCoverLocalIndex.kt`, `AlbumCoverThumbnailPlanner.kt`, `AlbumCoverBucketKeys.kt` |
| Badges nuvem | `ui/gallery/GalleryCloudBadge.kt` |
| Mapa | `ui/places/PlacesMapScreen.kt` |

## UI sync / settings / albums

| Responsabilidade | Arquivo |
|------------------|---------|
| Aba Sync | `ui/sync/SyncStatusScreen.kt` |
| Ajustes | `ui/settings/SettingsScreen.kt` |
| Álbuns grid | `ui/albums/AlbumsScreen.kt` |
| Welcome | `ui/welcome/WelcomeScreen.kt` |

## Manifest / build

| Responsabilidade | Arquivo |
|------------------|---------|
| Components | `app/src/main/AndroidManifest.xml` |
| Versão, deps | `app/build.gradle.kts` |
| Ship APK | `scripts/ship-apk.sh` |

## Busca rápida por símbolo

Use ripgrep no repo:

```bash
rg "runBidirectionalSync" app/
rg "ensureThumbnailCached" app/
rg "pendingDeleteTarget" app/
rg "longTaskMutex" app/
rg "isVisibleInGalleryBrowse" app/
```

## Relação com documentação

| Pasta doc | Tema |
|-----------|------|
| SYNC_PIPELINE.md | Fases coordinator |
| DATA_AND_INDEX.md | Room entity fields |
| THUMBNAILS_CACHE_AND_DOWNLOADS.md | ensureThumbnail*, prefetch |
| AI_AGENT_GUIDE.md | Invariantes normativos |
