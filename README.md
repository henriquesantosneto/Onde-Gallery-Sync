# OneGallery Sync

Galeria Android nativa (Kotlin + Jetpack Compose) que substitui a integração descontinuada **Galeria Samsung ↔ OneDrive**.

## Funcionalidades (alinhadas ao escopo definido)

- **Sincronização bidirecional** com Microsoft OneDrive (pasta `OneGallerySync/`).
- **Todas as pastas de mídia** indexadas via MediaStore (incl. **WhatsApp**, DCIM, etc., conforme visibilidade no sistema).
- **Galeria espelhada**: mesma grade para itens locais e só na nuvem.
- **Originais sob demanda**: download ao abrir o item ou pelo botão “Baixar original”.
- **Álbuns**, indicadores **nuvem / sync / offline**, aba de **status**.
- **Lixeira** ignorada na indexação.
- **Exclusões** só propagam após **confirmação** (diálogo ou aba Sync).
- **APK privado** para instalação no seu Samsung.

## Antes de compilar

1. Registre o app no Microsoft Entra — [docs/MICROSOFT_APP_REGISTRATION.md](docs/MICROSOFT_APP_REGISTRATION.md).
2. Atualize `auth_config.json`, `build.gradle.kts` (`MSAL_*`) e `AndroidManifest.xml` (redirect MSAL).
3. Abra no **Android Studio**, sync Gradle, execute no aparelho.

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
