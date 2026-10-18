# Arquitetura — OneGallery Sync

## Objetivo

Substituir a integração descontinuada **Galeria Samsung ↔ OneDrive** por um app Android nativo que:

- Espelha a biblioteca local (fotos/vídeos, **todas as pastas** indexadas pelo MediaStore).
- Planeja sync **bidirecional** com o OneDrive em `OneGallerySync/` na raiz do drive.
- Oferece álbuns, indicadores de nuvem/offline e fila de sync (em evolução).
- Respeita **lixeira/exclusões** e **confirmação** antes de propagar deletes.

## Camadas

```text
ui (Compose)
  ├── gallery / albums / sync status / settings
  └── auth gate (MSAL)

domain
  ├── SyncCoordinator — orquestra indexação (hoje) e sync (futuro)
  └── SyncPolicy — inclusão/exclusão de paths, confirmação de delete

data
  ├── LocalMediaRepository — MediaStore (imagens + vídeos)
  ├── Room — sync_metadata (estado LOCAL_ONLY / PENDING / …)
  └── OneDriveRepository — Retrofit + Graph API (probe; upload/download TBD)

auth
  └── MsalAuthManager — token para Graph

worker
  └── SyncWorker — placeholder WorkManager
```

## Fluxo de exibição (espelho + sob demanda)

1. **Grade local imediata** via URIs do MediaStore (miniaturas com Coil).
2. Metadados Room registram `cloudState` por item.
3. **Fase futura**: originais ausentes no dispositivo serão baixados on-demand ao abrir/visualizar; uploads enfileirados com WorkManager e rede Wi‑Fi/carga configurável.

## Sync bidirecional (roadmap)

| Etapa | Descrição |
|-------|-----------|
| 1 | Indexação local + auth Graph (**atual**) |
| 2 | Listagem recursiva OneDrive + diff por path/etag |
| 3 | Upload incremental (fila, retry, hash) |
| 4 | Download sob demanda + cache |
| 5 | Delete/trash com diálogo de confirmação |
| 6 | Conflitos (last-write-wins ou prompt) |
| 7 | Testes instrumentados + dogfooding no Samsung |

Flag central: `SyncCapability.bidirectionalSyncEnabled` — permanece `false` até a etapa 6–7.

## Exclusões

- Paths contendo lixeira (`.Trash`, `Lixeira`, etc.) são ignorados na indexação (`SyncPolicy.shouldIncludeLocalItem`).
- Propagação de exclusão exige confirmação quando afeta nuvem e dispositivo (`requiresDeleteConfirmation`).
