# Baixar o APK

## Link estável (após você confirmar **Sim** na conversa)

O agent roda `./scripts/ship-apk.sh` e publica no **GitHub Releases**. O link segue este padrão:

`https://github.com/henriquesantosneto/Onde-Gallery-Sync/releases/download/v{VERSÃO}-debug/OneGallerySync-debug.apk`

Exemplo (substitua pela versão atual em `app/build.gradle.kts`):

https://github.com/henriquesantosneto/Onde-Gallery-Sync/releases/download/v0.8.50-debug/OneGallerySync-debug.apk

Repositório: https://github.com/henriquesantosneto/Onde-Gallery-Sync

## Publicar manualmente

```bash
./scripts/ship-apk.sh
```

Requer `GITHUB_TOKEN` ou `gh auth login` com permissão de release no repo.

## Compilar no PC

```bash
./gradlew :app:assembleDebug
# saída: app/build/outputs/apk/debug/app-debug.apk
```

## Instalar no telemóvel

```bash
adb install -r OneGallerySync-debug.apk
```

Ou copie o APK para o Samsung e abra-o (permitir instalação de apps desconhecidos).
