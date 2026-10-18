# Baixar o APK

Os links do Origin **só funcionam com login** (repositório privado). Use uma destas opções:

## A) Preview desta agent (recomendado)

1. Na conversa do agent, abra o cartão **Preview** (servidor local).
2. Clique em **Baixar OneGallerySync-debug.apk**.

URL local (na VM do agent): `http://127.0.0.1:43621/OneGallerySync-debug.apk`

## B) Painel de ficheiros do agent

1. Abra a run: https://cursor.com/agents/bc-7c109117-5b37-4cc7-821d-1fabf8860dd7
2. No explorador de ficheiros do projeto, vá a `releases/OneGallerySync-debug.apk`.
3. Descarregue o ficheiro (menu do ficheiro / download).

## C) Clonar o repositório

```bash
origin repo clone henriquekako/tmp-a53cc6c3697b04a5
cd tmp-a53cc6c3697b04a5/releases
```

## D) Compilar no PC

```bash
./gradlew :app:assembleDebug
# saída: app/build/outputs/apk/debug/app-debug.apk
```

## Instalar no telemóvel

```bash
adb install -r OneGallerySync-debug.apk
```

Ou copie o APK para o Samsung e abra-o (permitir instalação de apps desconhecidos).
