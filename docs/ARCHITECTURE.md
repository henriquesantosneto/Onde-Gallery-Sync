# Arquitetura — OneGallery Sync

## Objetivo (escopo do projeto)

Galeria Android **nativa**, independente da Galeria Samsung, com **OneDrive**:

| Requisito | Implementação |
|-----------|----------------|
| Sync bidirecional | `SyncCoordinator.runBidirectionalSync()` — upload, diff por data, entradas só na nuvem |
| Todas as pastas | `MediaStore` imagens + vídeos (DCIM, WhatsApp, Downloads, etc.) |
| Galeria espelhada | UI única a partir de `sync_metadata` (local + nuvem) |
| Originais sob demanda | `downloadOriginalOnDemand()` ao abrir item / botão manual |
| Álbuns | Agrupamento por bucket/pasta |
| Offline / status / nuvem | Badges na grade; cache em `cache/originals`; sync pausa offline |
| Lixeira | Ignorada em `SyncPolicy.shouldIncludeLocalItem` |
| Exclusões | `pendingDeleteTarget` + diálogo / aba Sync antes de propagar |
| APK privado | `./gradlew :app:assembleRelease` |

Pasta no OneDrive: **`OneGallerySync/`** (espelha caminhos relativos, ex. `Pictures/WhatsApp/…`).

## Camadas

```text
ui (Compose) → gallery, álbuns, detalhe, sync, settings, MSAL gate
sync → SyncCoordinator, SyncPolicy, SyncPaths, WorkManager
data.local → MediaStore, cache de originais, Room
data.onedrive → MS Graph (listagem recursiva, upload, download, delete)
auth → MsalAuthManager
```

## Fluxo de sync

1. Indexar mídia local → linhas Room (`LOCAL_ONLY` ou estado anterior).
2. Garantir pasta `OneGallerySync` no drive.
3. Listar arquivos remotos recursivamente.
4. Para cada item local: upload se ausente/desatualizado; marcar `PENDING_DOWNLOAD` só se o OneDrive mudou desde a última sync (não basta a data de upload na nuvem ser mais nova que `DATE_ADDED` no celular).
5. Itens remotos sem par local → `CLOUD_ONLY`.
6. Detecção de remoção → fila `pendingDeleteTarget` (confirmação obrigatória).

## Honestidade sobre “completo”

O código implementa o pipeline descrito acima, mas **você** deve validar no Samsung (rede, permissões, conta Microsoft, pastas WhatsApp no Android 13+). Conflitos usam comparação simples de timestamp; casos extremos podem exigir ajustes.

Flag: `SyncCapability.bidirectionalSyncEnabled = true`.
