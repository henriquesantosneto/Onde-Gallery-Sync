# Miniaturas, cache e downloads

## Pipeline de miniatura (`ensureThumbnailCached`)

Ordem em `ensureThumbnailCachedOnIo`:

1. Já existe em `cache/thumbs/` → retorna.
2. **URI local** (MediaStore) → decode imagem ou frame de vídeo (`VideoThumbnailHelper`).
3. **Original em cache** (`cachedFilePath`) → gera miniatura localmente.
4. **Graph** (online, modo `ONEDRIVE_GRAPH`):
   - `downloadThumbnailToFile` (large, fallback medium)
   - Se falhar e for imagem: `downloadImagePrefixForThumbnail` (Range 0–2 MiB) + decode
5. Commit via `MediaCacheRepository.commitThumbnail` → dispara `onThumbnailCommitted`.

Parâmetros de qualidade local:

- Lado máximo **1280 px**, JPEG **88%** (`THUMB_CACHE_MAX_SIDE_PX`, `THUMB_CACHE_JPEG_QUALITY`).

## Prefetch pós-sync

`prefetchMissingThumbnails`:

| Parâmetro | Valor |
|-----------|--------|
| Itens por leva | 512 |
| Paralelismo | **3** (`PARALLEL_THUMBNAILS`) |
| Máx. levas por sync | 36 |
| Máx. miniaturas por sync | 2.500 |
| Levas extras após contagem | +8 |

Se ainda faltar após limites, mensagem orienta **sincronizar de novo**.

### UI durante prefetch

- Fase `PREFETCH_THUMBNAILS`, headline *“Completando galeria…”*.
- **`activeItems`**: no máximo 3 linhas com barras (lista backing + throttle de progresso 280 ms).
- **`runQueue`**: itens da leva atual.

### Anti-travamento (histórico de bugs)

- Progresso não lê/escreve `StateFlow` na IO thread para lista ativa — **backing list** + publish coalescido.
- Refresh de galeria em bulk a cada **5 s**, não a cada miniatura.
- Timeouts HTTP no Graph.

## Originais completos

- **Sob demanda** ao abrir detalhe ou botão explícito.
- `downloadOriginalOnDemand` / `recallOriginalToDevice` — não faz parte do prefetch em massa.
- Caminho salvo em `cachedFilePath` na entidade.

## Coil / grade

- `GalleryThumbnailImage` resolve modelo via `GalleryThumbnailModels` + opcionalmente `ensureThumbnailCached` ao compor célula.
- Vídeos: `VideoFrameDecoder` registrado na Application.

## Capas de álbum

- `ensureAlbumCoverThumbnails()` antes do prefetch geral — uma thumb prioritária por álbum (pastas + lugares).
- `AlbumCoverThumbnailPlanner` escolhe candidato que já pode gerar thumb.

## Armazenamento

Paths relativos ao cache interno do app (não expostos na galeria do sistema). Liberação de espaço local do **original** é política separada ([DELETES_AND_SPACE.md](DELETES_AND_SPACE.md)).

## Constantes relacionadas

Ver tabela completa em [CONFIGURATION_REFERENCE.md](CONFIGURATION_REFERENCE.md).
