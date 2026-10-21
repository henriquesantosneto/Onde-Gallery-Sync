# Desenvolvimento e release

## Requisitos

- JDK 17+
- Android SDK 34 (compile/target), minSdk **26**
- Gradle wrapper incluído (`./gradlew`)

## Configuração MSAL (obrigatório para Graph)

1. Copiar `msal.properties.example` → `msal.properties`
2. Preencher `MSAL_CLIENT_ID` — ver [BUILTIN_MSAL.md](BUILTIN_MSAL.md)
3. Registrar redirect — [AZURE_REDIRECT.md](AZURE_REDIRECT.md)

Keystore debug commitado em `keystore/debug.keystore` para hash de redirect estável.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:assembleRelease   # requer signing config release
```

APK debug: `app/build/outputs/apk/debug/app-debug.apk`

## Estrutura do repositório

```text
/workspace
├── app/src/main/java/com/onegallery/sync/   # Código Kotlin
├── app/src/main/res/                        # Recursos, auth_config.json
├── app/src/main/AndroidManifest.xml
├── docs/                                    # Documentação
├── scripts/                                 # ship-apk, push GitHub
├── releases/                                # APK publicado + LEIA-ME
├── keystore/
├── msal.properties.example
└── README.md
```

## Pacotes principais

| Pacote | Responsabilidade |
|--------|------------------|
| `sync` | Coordinator, policy, foreground, receivers |
| `data.local` | MediaStore, cache, geo, delete |
| `data.local.db` | Room |
| `data.onedrive` | Graph, SAF, mirror |
| `auth` | MSAL |
| `worker` | WorkManager |
| `ui.*` | Compose |

## Script `scripts/ship-apk.sh`

Automatiza release debug interna:

1. Lê versão de `app/build.gradle.kts`
2. `./gradlew :app:assembleDebug`
3. Copia para `releases/OneGallerySync-debug.apk`
4. Atualiza `releases/LEIA-ME.txt`
5. Commit/push + GitHub Release `v{version}-debug`

## Testes no dispositivo

Checklist manual sugerido:

- [ ] Permissões de mídia + (opcional) localização de mídia
- [ ] Login Graph + escolha de pasta
- [ ] Sync completo + aba Sync (fases e barras)
- [ ] Item cloud-only aparece após miniatura
- [ ] Download original no detalhe
- [ ] Exclusão com confirmação
- [ ] Boot / rede / import WhatsApp dispara sync (sem loop)
- [ ] Só Wi‑Fi respeitado

Não há suite de testes instrumentados extensa no repositório — validação é principalmente em dispositivo real (Samsung/Android 13+).

## ProGuard

Release: `minifyEnabled false` no `build.gradle.kts` atual.

## Contribuição / branches

Desenvolvimento ativo pode usar branch `cursor/*` no remoto Origin; `main` no GitHub recebe releases via script.

## Documentação

Índice completo: [docs/README.md](README.md)
