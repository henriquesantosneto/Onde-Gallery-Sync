# Multicloud — plano e estado

## Modelo de produto

- **Um provedor ativo por instalação:** OneDrive (Graph) **ou** Dropbox (futuro).
- Mesmo motor: galeria, Room, sync, recall, liberar espaço, mapa, background.
- Modos legados OneDrive (SAF, espelho `Pictures/`) permanecem fora do contrato API até eventual remoção.

## Fases

| Fase | Conteúdo | Estado |
|------|----------|--------|
| **1** | `CloudSyncBackend`, `RemoteCloudFile`, adapter Graph | **Em curso** — list/upload/download/delete via backend |
| **2** | `DropboxBackend` (OAuth PKCE, cursor, content_hash) | Stub apenas |
| **3** | UX escolher provedor + pasta | Pendente |
| **4** | Paridade completa no coordinator (menos `when (syncMode)`) | Pendente |
| **5** | App Console Dropbox, rate limits | Pendente |
| **6** | Room: `cloudProvider` + `remoteItemId` (migração) | Pendente |

## Fase 1 — ficheiros

| Peça | Caminho |
|------|---------|
| Contrato | `data/cloud/CloudSyncBackend.kt` |
| Modelo remoto | `data/cloud/RemoteCloudFile.kt`, `RemoteContentHashKind.kt` |
| OneDrive Graph | `data/cloud/OneDriveGraphCloudBackend.kt` |
| Dropbox (stub) | `data/cloud/DropboxCloudBackendStub.kt` |
| Seleção | `data/cloud/CloudSyncBackendRegistry.kt` |
| Preferência | `SyncPreferences.cloudProvider` (`ONEDRIVE` default) |

O `SyncCoordinator` usa o registry para **ONEDRIVE_GRAPH** em listagem incremental, upload, download e delete. EXIF probe, browser de pasta e MSAL continuam em `OneDriveRepository` / `auth/` até Fase 3–4.

## Paridade alvo (Graph hoje = referência)

Conta → pasta de sync → delta/cursor → upload paralelo → só miniatura no sync → original no recall → hash+tamanho → liberar espaço → background.

Dropbox: `content_hash` + cursor `list_folder`, sem quickXorHash.

## Próximo passo natural (Fase 2)

Implementar `DropboxCloudBackend` real e testes de list/upload/delete; só então expor toggle em Ajustes.
