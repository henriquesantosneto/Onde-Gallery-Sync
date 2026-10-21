# Instruções para agentes de IA (Cursor, Cloud Agent, etc.)

Este repositório **OneGallery Sync** tem documentação pensada para humanos e para IA. Antes de alterar sync, galeria, background ou OneDrive, **leia e siga** os documentos abaixo — não improvise arquitetura paralela.

## Leitura obrigatória (ordem)

1. **[docs/AI_AGENT_GUIDE.md](docs/AI_AGENT_GUIDE.md)** — invariantes, anti-padrões, checklist, roteiro por tipo de mudança.
2. **[docs/CODE_MAP.md](docs/CODE_MAP.md)** — qual arquivo editar para cada comportamento.
3. **[docs/ARCHITECTURE.md](docs/ARCHITECTURE.md)** — princípio `sync_metadata` como índice único.
4. **[docs/CONFIGURATION_REFERENCE.md](docs/CONFIGURATION_REFERENCE.md)** — constantes; altere aqui + doc se mudar limites.

Índice completo: **[docs/README.md](docs/README.md)**.

## Regras invioláveis (resumo)

| # | Regra |
|---|--------|
| 1 | A UI da galeria deriva de **`sync_metadata` (Room)**, não diretamente de MediaStore ou Graph. |
| 2 | Item **só na nuvem** entra na grade/álbuns só com **miniatura em cache** (`GalleryBrowseVisibility`). |
| 3 | Exclusões usam **`pendingDeleteTarget`** — nunca apagar local/remoto sem confirmação do fluxo existente. |
| 4 | Um sync longo por vez: **`longTaskMutex`**; não iniciar segundo `runBidirectionalSync` concorrente. |
| 5 | **`activeItems`** de progresso: usar **`activeSyncItemsBacking`**, nunca ler/escrever `_state.activeItems` a partir de threads IO para upsert/remove. |
| 6 | Background sync: respeitar **`shouldDeferBackgroundSyncByTime`**, **`shouldDeferAutomaticBidirectionalSync`**, supressão do **MediaLibraryChangeWatcher** durante sync/release. |
| 7 | Modo nuvem: ramificar por **`SyncMode`** nos métodos delegados do coordinator (list/upload/download/delete). |
| 8 | Não duplicar gatilho de auto-sync no app start (`OneGalleryApp` vs `GalleryViewModel` — um caminho só). |
| 9 | APK/release: regra **[.cursor/rules/apk-on-sim.mdc](.cursor/rules/apk-on-sim.mdc)** — não publicar sem **Sim** explícito do usuário. |

## Fluxo de trabalho recomendado para a IA

```text
Entender pedido → AI_AGENT_GUIDE (roteiro) → CODE_MAP (arquivos)
    → ler código existente no padrão do repo → diff mínimo
    → ./gradlew :app:assembleDebug se mudou Kotlin
    → atualizar doc se mudou comportamento/invariante/constante
```

## O que atualizar quando mudar comportamento

| Mudança | Atualizar |
|---------|-----------|
| Nova fase de sync / ordem pós-sync | `SYNC_PIPELINE.md`, `SyncProgressPhase.kt` |
| Novo gatilho background | `BACKGROUND_AND_TRIGGERS.md` |
| Visibilidade galeria / álbum | `GALLERY_AND_ALBUMS.md`, `GalleryBrowseVisibility.kt` |
| Miniaturas / paralelismo | `THUMBNAILS_CACHE_AND_DOWNLOADS.md`, `CONFIGURATION_REFERENCE.md` |
| Constante numérica | `SyncCoordinator` / prefs + `CONFIGURATION_REFERENCE.md` |
| Invariante nova | `AI_AGENT_GUIDE.md` + este `AGENTS.md` |

## Build rápido

```bash
./gradlew :app:assembleDebug
```

MSAL: `msal.properties` (ver `docs/BUILTIN_MSAL.md`).
