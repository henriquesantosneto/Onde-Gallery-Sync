# Galeria, álbuns e lugares

## ViewModel único

`GalleryViewModel` agrega:

- `syncCoordinator.galleryMetadata` → itens deduplicados e filtrados
- `userAlbums`
- `galleryDisplayRefreshEpoch` — invalida mapeamento quando cache muda
- `albumCoverRevisions` — força recomputar capas por `bucketId`
- Preferências locais: ordenação, filtros, seleção múltipla

Não há ViewModels separados por tela; Sync e Settings falam direto com `SyncCoordinator`.

## Visibilidade na grade (`GalleryBrowseVisibility`)

Regra central: **item só na nuvem** (sem URI local, sem original em cache) **só aparece** na galeria/álbuns quando existe **miniatura em disco**:

```kotlin
// GalleryBrowseVisibility.kt — resumo
if (localUri != null) return true
if (cachedOriginal válido) return true
return hasCachedThumbnailFile(...)
```

Isso evita células vazias enquanto o prefetch baixa miniaturas.

## Refresh durante prefetch em massa

Quando o pós-sync baixa milhares de miniaturas:

- **`notifyGalleryDisplayRefresh()`** imediato fora do bulk.
- **Dentro do bulk:** refresh da galeria **coalescido a cada 5 s** (`GALLERY_DISPLAY_REFRESH_COALESCE_MS`).
- **Capas de álbum:** revisão por caminho com debounce **280 ms** (`scheduleAlbumCoverRevisionForPath`).

Ao terminar uma leva de prefetch, refresh final + `flushPendingAlbumCoverRevisions`.

## Álbuns de pasta

- Agrupamento por `albumName` (bucket MediaStore / pasta lógica).
- `buildAlbums()` → `MediaAlbum` com contagem foto/vídeo, `sortTimestamp`, capa.
- Capa: `AlbumCoverLocalIndex` + `resolveAlbumCoverItem` (prioriza item com miniatura local recente).
- Tile na `AlbumsScreen` usa `coverRevision` e `liveAlbumCoverFor()` para capa ao vivo ao rolar.

## Álbuns de lugar

- Agrupamento por `PlaceComponents` / `PlaceMatchKey` (label de geocoder ou GPS).
- Mesma mecânica de capa com prefixo `place:` no `bucketId`.

## Álbuns do usuário

- CRUD via `SyncCoordinator` + `UserAlbumEntity`.
- **Mover** fotos entre álbuns altera só `userAlbumName` — **não move** arquivo no OneDrive.

## Telas principais

| Tela | Arquivo | Função |
|------|---------|--------|
| Grade | `GalleryScreen.kt` | Timeline, zoom, seleção, badges nuvem |
| Álbuns | `AlbumsScreen.kt` | Grid pasta + lugares |
| Detalhe | `MediaDetailScreen.kt` | Original, EXIF, baixar, excluir |
| Mapa | `PlacesMapScreen.kt` | MapLibre, clusters, pins |
| Sync | `SyncStatusScreen.kt` | Status, fila, barras paralelas |

## Badges e estados

`GalleryCloudBadge.kt` — overlay conforme `CloudSyncState` e se há original local visível.

## Ordenação

- Galeria: `GallerySortOrder` (data captura, data adição, nome).
- Lista de álbuns: `AlbumListSort`.

## Mapa

- Pins a partir de itens com lat/long no índice.
- `PhotoMapPinIndex` / clustering para performance.
- Estilo satélite opcional (`SatelliteMapStyle.kt`).

## Navegação aninhada

Ver [UI_NAVIGATION.md](UI_NAVIGATION.md): filtro `GalleryBrowseFilter.AlbumFolder` / `Place` reutiliza `GalleryScreen` dentro da aba Álbuns.
