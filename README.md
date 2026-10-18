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

## Arquitetura

Ver [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md).

## Nota importante

A sincronização está **implementada no código**, mas depende do registro Microsoft, permissões de mídia no Android e testes reais no dispositivo. Não há garantia de paridade 100% com a antiga integração Samsung até você validar suas pastas e volume de arquivos.
