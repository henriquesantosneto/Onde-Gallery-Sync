# Guia para agentes de IA — manutenção correta do OneGallery Sync

Documento normativo: ao implementar ou corrigir código, a IA **deve** alinhar-se a estes fatos e invariantes. Se o código e este guia divergirem, **corrigir o código ou atualizar este guia** no mesmo PR/commit.

## 1. Modelo mental (não negociável)

```text
MediaStore ──┐
             ├──► SyncCoordinator ──► sync_metadata (Room) ──► GalleryViewModel ──► Compose
Graph/SAF ───┘              │
                            ├──► cache/thumbs, cache/originals (MediaCacheRepository)
                            └──► SyncUiState (aba Sync)
```

- **Nunca** fazer a grade ler MediaStore ou Graph diretamente para listar a biblioteca espelhada.
- **Pode** ler MediaStore/Graph dentro do coordinator ou repositórios para **atualizar** Room/cache.

## 2. Invariantes de sincronização

### 2.1 Mutex e deferrals

| Invariante | Implementação |
|------------|----------------|
| Um pipeline longo principal | `SyncCoordinator.longTaskMutex` |
| Auto-sync não compete | `shouldDeferAutomaticBidirectionalSync()` — usado por `SyncWorker` e `runBidirectionalSync` |
| Intervalo mínimo auto bidirecional | `SyncPreferences.AUTO_BIDIRECTIONAL_MIN_INTERVAL_MS` (12 min) |
| Cooldown pós-cancelamento | `CANCEL_COOLDOWN_MS` |
| Pós-sync enrichment | `runPostSyncEnrichmentInternal` **inline** após núcleo (mesmo `longTaskMutex` + FGS); não agendar async solto |

### 2.2 Loop infinito (regressões conhecidas)

**Não fazer:**

- Disparar `runBidirectionalSync` em **dois lugares** no cold start (ex.: `OneGalleryApp` + `GalleryViewModel.init`).
- Reagir ao MediaStore **durante** sync sem supressão (`MEDIA_LIBRARY_SUPPRESS_DURING_SYNC_MS`).
- Reagir ao MediaStore logo após **liberação de espaço local** sem janela de supressão.
- Atualizar `galleryMetadata` na UI **sem debounce** (450 ms) em mass upsert.

**Fazer:**

- Consultar `MediaLibraryChangeWatcher` e `SyncPreferences.shouldDeferBackgroundSyncByTime()` antes de enfileirar work.
- Usar `BackgroundSyncScheduler.enqueueSyncSoon` com REPLACE para debounce.

### 2.3 Reconciliação

- Upload se ausente/desatualizado no remoto; comparação usa timestamp (+ tolerância ~2 s), tamanho, **QuickXorHash** quando disponível.
- Nuvem mais nova → `PENDING_DOWNLOAD` / placeholder; **original completo não** no prefetch em massa.
- Remoção detectada → **`pendingDeleteTarget`**, não delete imediato bilateral.

### 2.4 Modos (`SyncMode`)

Alterações em upload/list/download/delete **devem** passar pelos switches centralizados no coordinator (`listRemoteFiles`, `uploadLocalFile`, `downloadRemoteFile`, `deleteRemoteFile`). Não chamar `OneDriveRepository` da UI.

## 3. Invariantes de UI / galeria

### 3.1 Visibilidade

Arquivo: `GalleryBrowseVisibility.kt`

- Com `localUri` ou original em cache → visível.
- Cloud-only → **exige** arquivo em `cache/thumbs` (`hasCachedThumbnailFile`).

Alterar visibilidade **sem** alterar esta função (ou o mapper) quebra o contrato documentado em `GALLERY_AND_ALBUMS.md`.

### 3.2 Refresh e capas

| Evento | Comportamento esperado |
|--------|-------------------------|
| Miniatura commitada (fora bulk) | `notifyGalleryDisplayRefresh()` + `scheduleAlbumCoverRevisionForPath` |
| Miniatura commitada (bulk prefetch) | **Não** refresh imediato da galeria; coalesce **5 s** (`GALLERY_DISPLAY_REFRESH_COALESCE_MS`); capas ainda agendadas |
| Fim do prefetch bulk | `notifyGalleryDisplayRefresh()` + `flushPendingAlbumCoverRevisions()` |

### 3.3 Progresso paralelo (aba Sync)

Arquivo: `SyncCoordinator` — `activeSyncItemsBacking`, `scheduleActiveItemsPublish()`

**Proibido:** `upsertActiveSyncItem` ler `_state.value.activeItems` em thread IO (race → lista infinita ou itens fantasma).

**Obrigatório:** `removeActiveSyncItem` em `finally` após cada transferência/thumbnail worker.

Throttle progresso: `ACTIVE_ITEM_PROGRESS_MIN_INTERVAL_MS` (280 ms).

Paralelismo miniaturas: **`PARALLEL_THUMBNAILS = 3`** (semáforo em `runMissingThumbnailPrefetch`).

## 4. Invariantes de miniaturas e rede

Ordem em `ensureThumbnailCachedOnIo`: cache → local URI → cached original → Graph thumb → prefix image.

- Graph HTTP client **com timeouts** (connect/read/call) — não remover sem substituir.
- Prefetch limitado por sync: `THUMB_PREFETCH_MAX_TOTAL_PER_SYNC`, `THUMB_PREFETCH_MAX_ROUNDS` — evita ficar preso só em miniaturas.

## 5. Invariantes de background

| Componente | Constante / nome |
|------------|------------------|
| Periodic work | `SyncWorker.UNIQUE_NAME`, 15 min |
| One-shot | `IMMEDIATE_UNIQUE`, delay típico 30 s |
| Boot delay | ~90 s |
| Watcher debounce | 8 s; delay sync 45 s |

Work só se `backgroundSyncAlwaysEnabled && hasCompletedSetup && !isAutoSyncPaused()`.

Foreground: `SyncForegroundGuard` ref-count durante sync longo.

## 6. Roteiro: onde implementar X

| Pedido do usuário | Ler primeiro | Arquivos principais |
|-------------------|--------------|---------------------|
| Sync trava / loop | §2.2, BACKGROUND_AND_TRIGGERS | `SyncCoordinator`, `OneGalleryApp`, `GalleryViewModel`, `MediaLibraryChangeWatcher` |
| Falta mídia na nuvem | ONEDRIVE_INTEGRATION, SYNC_PIPELINE | `OneDriveRepository`, reconciliação no coordinator |
| Nuvem não aparece na galeria | GALLERY_AND_ALBUMS, THUMBNAILS | `GalleryBrowseVisibility`, prefetch, `GalleryMapper` |
| Barras paralelas erradas | §3.3 | `SyncCoordinator`, `SyncStatusScreen` |
| ANR / UI congela | §3.3, THUMBNAILS | throttle progress, bulk refresh, debounce metadata |
| Exclusão | DELETES_AND_SPACE | `pendingDeleteTarget`, `MediaDeleteRequestRouter` |
| Nova preferência | CONFIGURATION_REFERENCE | `SyncPreferences`, `SettingsScreen` |
| Novo modo nuvem | ONEDRIVE_INTEGRATION | `SyncMode`, coordinator switches, setup UI |

Mapa expandido: [CODE_MAP.md](CODE_MAP.md).

## 7. Anti-padrões (histórico real do projeto)

| Anti-padrão | Por que falhou |
|-------------|----------------|
| Banner global de sync em todas as abas | Usuário pediu revert — progresso **só** aba Sync |
| 8 miniaturas paralelas + post Main a cada byte | ANR / travamento |
| Refresh galeria a cada thumb no bulk | UI overload; substituído por coalesce 5 s |
| `getAll()` Room a cada leva de prefetch sem limite | Sync “eterno” só em miniaturas |
| Ler `StateFlow.activeItems` na IO para upsert | Lista “Transferências em paralelo” crescia infinito |

## 8. Checklist antes de concluir tarefa (IA)

- [ ] Mudança respeita índice Room como fonte da UI?
- [ ] Cloud-only ainda exige thumb para aparecer?
- [ ] Mutex/deferral/watcher supression intactos?
- [ ] `activeItems` usa backing list se tocado?
- [ ] Constantes alteradas refletidas em `CONFIGURATION_REFERENCE.md`?
- [ ] Comportamento novo descrito em doc temático ou § invariante aqui?
- [ ] `./gradlew :app:assembleDebug` OK?
- [ ] APK: só se usuário disse **Sim** para publicar (`.cursor/rules/apk-on-sim.mdc`)?

## 9. Testes manuais sugeridos (pós-mudança em sync/UI)

1. Sync manual completo — fases na aba Sync.
2. Pós-sync miniaturas — máx. 3 barras; contador não cresce infinito.
3. Álbum cloud enche após ~5 s durante prefetch.
4. Background: boot/rede não entram em loop (12 min / supressão).
5. Item cloud-only aparece após thumb; original só no detalhe.

## 10. Documentos irmãos

- [ARCHITECTURE.md](ARCHITECTURE.md)
- [SYNC_PIPELINE.md](SYNC_PIPELINE.md)
- [CONFIGURATION_REFERENCE.md](CONFIGURATION_REFERENCE.md)
- [CODE_MAP.md](CODE_MAP.md)
