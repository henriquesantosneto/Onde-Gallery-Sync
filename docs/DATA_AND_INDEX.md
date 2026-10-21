# Dados e índice da galeria

A **fonte da verdade da UI** é a tabela Room `sync_metadata`, não o MediaStore nem a listagem remota isoladamente.

## Room

- **Database:** `onegallery_sync.db`, versão **10** (`AppDatabase.kt`).
- **Migração:** destructive fallback (reinstalação limpa em upgrade de schema).

### `SyncMetadataEntity`

Chave primária: **`canonicalPath`** — caminho lógico estável que espelha a hierarquia no OneDrive (ex.: `Pictures/WhatsApp/IMG_….jpg`).

| Campo | Significado |
|-------|-------------|
| `displayName` | Nome do arquivo |
| `albumName` | Pasta/bucket para álbum de dispositivo |
| `userAlbumName` | Álbum criado pelo usuário (só organização local; nuvem inalterada) |
| `localMediaId` / `localContentUri` | Ligação MediaStore |
| `oneDriveItemId` | Id Graph do item |
| `localModifiedAt` / `remoteModifiedAt` | Segundos epoch |
| `capturedAtSeconds` | Data de captura (EXIF / DATE_TAKEN) |
| `localQuickXorHash` / `remoteQuickXorHash` | Hash para decidir upload |
| `localSizeBytes` / `remoteSizeBytes` | Tamanhos |
| `cloudState` | Ver enum abaixo |
| `cachedFilePath` | Original completo em cache local (se baixado) |
| `pendingDeleteTarget` | Exclusão aguardando confirmação |
| `lastError` | Última falha de sync |
| `locationLabel`, `latitude`, `longitude` | Lugar / mapa |
| `videoDurationMs` | Duração de vídeo |
| `localReleasedForSpace` | Usuário/sistema removeu cópia local de propósito |
| `localLastAccessedAtMs` | Para liberação automática por inatividade |

Índices: `cloudState`, `localMediaId`, `oneDriveItemId`.

### `UserAlbumEntity`

Álbuns manuais (título, id, timestamps). Itens referenciam via `userAlbumName` na linha de metadata.

### DAOs

- `SyncMetadataDao`: observe flows (metadata da galeria, fila pendente LIMIT 200, contagens).
- `UserAlbumDao`: CRUD álbuns do usuário.

## Caminhos canônicos (`SyncPaths`)

- Normalizam prefixos (`Pictures/`, DCIM, WhatsApp encurtado, etc.).
- Raiz lógica na nuvem: pasta **`OneGallerySync/`** (`SyncPolicy.ONEDRIVE_MIRROR_ROOT`) no modo layout padrão; no Graph o usuário pode escolher **outra pasta raiz** (`GraphFolderPreferences`).

## Estados na nuvem (`CloudSyncState`)

| Estado | UI / sync |
|--------|-----------|
| `LOCAL_ONLY` | Só no aparelho (ainda não enviado ou modo sem nuvem) |
| `SYNCED` | Par local ↔ remoto alinhado |
| `PENDING_UPLOAD` | Na fila para envio |
| `PENDING_DOWNLOAD` | Nuvem mais nova; original opcional |
| `CLOUD_ONLY` | Só na nuvem (sem arquivo local) |
| `ERROR` | Falha registrada em `lastError` |

## Exclusões (`PendingDeleteTarget`)

| Valor | Significado |
|-------|-------------|
| `NONE` | Sem exclusão pendente |
| `LOCAL` | Propagar remoção no aparelho |
| `REMOTE` | Propagar remoção no OneDrive |
| `BOTH` | Ambos |

Nada é apagado até confirmação na UI (detalhe em [DELETES_AND_SPACE.md](DELETES_AND_SPACE.md)).

## Cache de arquivos (`MediaCacheRepository`)

Diretórios sob cache do app:

- **`cache/thumbs/`** — miniaturas JPEG derivadas ou baixadas do Graph.
- **`cache/originals/`** — cópias completas baixadas sob demanda.

Commit atômico: arquivo staging → rename; callback `onThumbnailCommitted` avisa o coordinator.

## Fluxo de dados para a UI

```text
MediaStore / Graph listing
        ↓
SyncCoordinator (upsert, reconcile)
        ↓
sync_metadata (Room)
        ↓
galleryMetadata Flow (debounce 450ms)
        ↓
GalleryMapper → GalleryDisplayItem
        ↓
GalleryViewModel (filtros, álbuns, mapa)
        ↓
Compose (GalleryScreen, AlbumsScreen, …)
```

## Deduplicação

`GalleryDisplayDedupe` remove duplicatas lógicas quando o mesmo conteúdo aparece com chaves diferentes (prioriza item com `oneDriveItemId`, etc.).

## Manutenção

- `compactDuplicateIndexRows()` — compactação de duplicatas no índice.
- `runIndexSanitation()` — passagem de saneamento pós-reconciliação.
