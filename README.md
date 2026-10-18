# OneGallery Sync

App Android nativo (Kotlin + Jetpack Compose) para usar o OneDrive como espelho da galeria, independente da integração descontinuada da Galeria Samsung.

## O que esta alpha faz

- Login com conta Microsoft (MSAL).
- Leitura de fotos e vídeos via **MediaStore** (inclui pastas expostas ao sistema, ex.: WhatsApp, DCIM, Downloads).
- Álbuns agrupados por bucket do sistema.
- Indexação local em SQLite (Room) com estado `LOCAL_ONLY`.
- UI de status de sync com aviso explícito: **sync bidirecional ainda não está habilitado**.

## O que ainda não faz

- Upload/download de arquivos com o OneDrive.
- Sincronização bidirecional, fila offline completa, resolução de conflitos.
- Propagação de exclusões (há política documentada, UI de confirmação pendente).

Veja [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) e [docs/MICROSOFT_APP_REGISTRATION.md](docs/MICROSOFT_APP_REGISTRATION.md).

## Requisitos

- Android Studio Ladybug ou mais recente.
- JDK 17.
- Dispositivo Samsung (ou emulador) com **API 26+**.
- Registro de app no Microsoft Entra / Azure com redirect Android configurado.

## Configuração rápida

1. Clone o repositório e abra a pasta raiz no Android Studio.
2. Siga [docs/MICROSOFT_APP_REGISTRATION.md](docs/MICROSOFT_APP_REGISTRATION.md) e atualize:
   - `app/src/main/res/raw/auth_config.json`
   - `MSAL_*` em `app/build.gradle.kts`
   - `BrowserTabActivity` no `AndroidManifest.xml`
3. **Sync Project with Gradle Files**.
4. Execute no dispositivo (`Run ▶`).

## Build APK privado

```bash
./gradlew :app:assembleRelease
```

Assine com seu keystore de release antes de distribuir no seu Samsung.

## Estrutura de pacotes

```text
com.onegallery.sync
├── auth          MSAL
├── data.local    MediaStore + Room
├── data.onedrive Graph API (Retrofit)
├── sync          políticas e coordenador
├── ui            Compose
└── worker        WorkManager (placeholder)
```

## Licença

Uso pessoal / projeto privado — ajuste conforme sua necessidade.
