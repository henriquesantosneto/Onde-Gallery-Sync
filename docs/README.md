# Documentação — OneGallery Sync

Índice da documentação técnica do repositório. Tudo aqui descreve o **comportamento implementado no código** (Kotlin/Android), não marketing de produto.

## Visão geral

| Documento | Conteúdo |
|-----------|----------|
| [ARCHITECTURE.md](ARCHITECTURE.md) | Camadas, componentes principais, diagramas, princípios de design |
| [SYNC_PIPELINE.md](SYNC_PIPELINE.md) | Fases do sync bidirecional, reconciliação, fila, pós-sync |
| [BACKGROUND_AND_TRIGGERS.md](BACKGROUND_AND_TRIGGERS.md) | WorkManager, boot, MediaStore, rede, foreground service |
| [ONEDRIVE_INTEGRATION.md](ONEDRIVE_INTEGRATION.md) | Modos `SyncMode`, Graph, SAF, espelho local, listagem delta |
| [DATA_AND_INDEX.md](DATA_AND_INDEX.md) | Room, `sync_metadata`, caminhos canônicos, estados na nuvem |
| [GALLERY_AND_ALBUMS.md](GALLERY_AND_ALBUMS.md) | UI da galeria, visibilidade, álbuns, lugares, mapa |
| [THUMBNAILS_CACHE_AND_DOWNLOADS.md](THUMBNAILS_CACHE_AND_DOWNLOADS.md) | Miniaturas, cache, originais sob demanda, limites |
| [DELETES_AND_SPACE.md](DELETES_AND_SPACE.md) | Exclusões pendentes, liberação de espaço local |
| [UI_NAVIGATION.md](UI_NAVIGATION.md) | Rotas Compose, telas, fluxo de setup |
| [CONFIGURATION_REFERENCE.md](CONFIGURATION_REFERENCE.md) | Constantes, preferências, paralelismo, debounces |
| [DEVELOPMENT.md](DEVELOPMENT.md) | Build, MSAL, scripts de release, estrutura de pastas |

## Microsoft / Azure (setup do desenvolvedor)

| Documento | Conteúdo |
|-----------|----------|
| [BUILTIN_MSAL.md](BUILTIN_MSAL.md) | Client ID embutido via `msal.properties` |
| [MICROSOFT_APP_REGISTRATION.md](MICROSOFT_APP_REGISTRATION.md) | Registro do app no Entra ID |
| [AZURE_REDIRECT.md](AZURE_REDIRECT.md) | URI de redirect e hash de assinatura |
| [AZURE_PERMISSIONS.md](AZURE_PERMISSIONS.md) | Permissões Graph |
| [entra-app-manifest-snippet.json](entra-app-manifest-snippet.json) | Snippet de manifest |

## Distribuição

| Documento | Conteúdo |
|-----------|----------|
| [DOWNLOAD_APK.md](DOWNLOAD_APK.md) | Instalação do APK debug/release |
| [../releases/LEIA-ME.txt](../releases/LEIA-ME.txt) | URL da release atual (gerado pelo script `ship-apk.sh`) |

## Leitura recomendada

1. **Novo no projeto:** [ARCHITECTURE.md](ARCHITECTURE.md) → [SYNC_PIPELINE.md](SYNC_PIPELINE.md) → [DATA_AND_INDEX.md](DATA_AND_INDEX.md).
2. **Problema de sync em background:** [BACKGROUND_AND_TRIGGERS.md](BACKGROUND_AND_TRIGGERS.md) + [CONFIGURATION_REFERENCE.md](CONFIGURATION_REFERENCE.md).
3. **Galeria não mostra item da nuvem:** [GALLERY_AND_ALBUMS.md](GALLERY_AND_ALBUMS.md) + [THUMBNAILS_CACHE_AND_DOWNLOADS.md](THUMBNAILS_CACHE_AND_DOWNLOADS.md).
4. **Integração OneDrive:** [ONEDRIVE_INTEGRATION.md](ONEDRIVE_INTEGRATION.md) + docs Azure acima.
