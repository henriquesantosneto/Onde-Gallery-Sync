# OneGallery Sync

Galeria Android nativa (Kotlin + Jetpack Compose) que substitui a integração descontinuada **Galeria Samsung ↔ OneDrive**.

## Funcionalidades (alinhadas ao escopo definido)

- **Sincronização bidirecional** com Microsoft OneDrive (pasta `OneGallerySync/`).
- **Todas as pastas de mídia** indexadas via MediaStore (incl. **WhatsApp**, DCIM, etc., conforme visibilidade no sistema).
- **Galeria espelhada**: mesma grade para itens locais e só na nuvem.
- **Originais sob demanda**: download ao abrir o item ou pelo botão “Baixar original”.
- **Álbuns** (pastas do aparelho + álbuns personalizados), **ordenar** por data ou nome, **mover** fotos entre álbuns (organização local — caminho no OneDrive não muda), **excluir** em lote (toque longo na grade).
- Indicadores **nuvem / sync / offline**, aba de **status**.
- **Lixeira** ignorada na indexação.
- **Exclusões** só propagam após **confirmação** (diálogo ou aba Sync).
- **APK privado** para instalação no seu Samsung.

## OneDrive (estilo ES File Explorer)

Quem **usa** o APK só faz **login Microsoft** e escolhe a pasta na nuvem. Quem **compila** configura o Client ID uma vez em `msal.properties` — [docs/BUILTIN_MSAL.md](docs/BUILTIN_MSAL.md).

Alternativa sem login: **Pictures/OneGallerySync** + backup de fotos no app OneDrive.

## Antes de compilar

1. Copie `msal.properties.example` → `msal.properties` e preencha `MSAL_CLIENT_ID` (registro gratuito — [docs/BUILTIN_MSAL.md](docs/BUILTIN_MSAL.md)).
2. Detalhes MSAL/redirect: [docs/MICROSOFT_APP_REGISTRATION.md](docs/MICROSOFT_APP_REGISTRATION.md).
3. `./gradlew :app:assembleDebug`

## Comandos

```bash
./gradlew :app:assembleDebug    # debug
./gradlew :app:assembleRelease  # APK release (assine o keystore)
```

## Configurações no app

- **Sync só em Wi‑Fi** (padrão ligado) — Ajustes.
- **Sincronizar agora** — aba Sync ou Ajustes.
- Exclusões detectadas na sync aparecem em **Sync → aguardando confirmação**.

## Documentação

Documentação técnica completa (arquitetura, sync, background, OneDrive, galeria, miniaturas, exclusões, UI, constantes, desenvolvimento):

**[docs/README.md](docs/README.md)** — índice de todos os documentos.

**Agentes de IA:** começar por **[AGENTS.md](AGENTS.md)** e **[docs/AI_AGENT_GUIDE.md](docs/AI_AGENT_GUIDE.md)** (invariantes e checklist antes de alterar código).

Destaques:

- [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) — visão geral e diagramas
- [docs/SYNC_PIPELINE.md](docs/SYNC_PIPELINE.md) — fases do sync e pós-sync
- [docs/BACKGROUND_AND_TRIGGERS.md](docs/BACKGROUND_AND_TRIGGERS.md) — WorkManager, boot, MediaStore
- [docs/ONEDRIVE_INTEGRATION.md](docs/ONEDRIVE_INTEGRATION.md) — modos Graph / SAF / espelho
- [docs/GALLERY_AND_ALBUMS.md](docs/GALLERY_AND_ALBUMS.md) — grade, álbuns, refresh durante download
- [docs/THUMBNAILS_CACHE_AND_DOWNLOADS.md](docs/THUMBNAILS_CACHE_AND_DOWNLOADS.md) — miniaturas e originais
- [docs/CONFIGURATION_REFERENCE.md](docs/CONFIGURATION_REFERENCE.md) — limites e debounces
- [docs/DEVELOPMENT.md](docs/DEVELOPMENT.md) — build e `ship-apk.sh`

## Nota importante

A sincronização está **implementada no código**, mas depende do registro Microsoft, permissões de mídia no Android e testes reais no dispositivo. Não há garantia de paridade 100% com a antiga integração Samsung até você validar suas pastas e volume de arquivos.
