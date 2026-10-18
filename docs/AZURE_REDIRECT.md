# URI de redirecionamento (Android / MSAL)

## Valores do APK GitHub (`OneGallerySync-debug.apk`)

Cada build calcula hash e URI a partir do **keystore de debug** usado na compilação (Gradle → manifest + MSAL). Abra o app **0.5.4+** → **Conectar OneDrive** e copie o card **“Registre no Azure”** — é a fonte da verdade do APK instalado.

Build deste repositório (CI / keystore debug padrão do ambiente):

| | |
|--|--|
| **Pacote** | `com.onegallery.sync` |
| **Hash (campo assinatura Android)** | `pH93hJ0fsCAwFF5NxVPK0Snx6Ws=` |
| **URI completa** | `msauth://com.onegallery.sync/pH93hJ0fsCAwFF5NxVPK0Snx6Ws%3D` |

Documentação antiga citava `XNT/…` por erro ao ler o certificado com `keytool -jarfile` em APKs v2. Use **`apksigner verify --print-certs`** ou o card no app.

Se você compilar localmente com outro keystore, o hash muda — registre a URI que o app **0.5.4+** mostrar.

---

## Caminho A — aparece **Android** no portal

1. https://entra.microsoft.com/ (ou portal.azure.com) → **Aplicativos** → **Registros de aplicativo** → seu app.
2. **Gerenciar** → **Autenticação**.
3. **Adicionar URI de redirecionamento** ou **Adicionar uma plataforma** → tile **Android**.
4. Pacote: `com.onegallery.sync`  
   Hash: o valor do card no app (ex.: `pH93hJ0fsCAwFF5NxVPK0Snx6Ws=`, com `/` e `=`, sem `%2F`).
5. **Configurar** / **Salvar**.

---

## Caminho B — **não tem Android** (comum com conta pessoal / UI nova)

Use **Aplicativos móveis e de desktop** (Mobile and desktop applications):

1. **Autenticação** → **Adicionar URI de redirecionamento** (ou **Adicionar uma plataforma**).
2. Escolha **Aplicativos móveis e de desktop** / **Mobile and desktop applications**.
3. Em **URIs de redirecionamento personalizados** / **Custom redirect URIs**, clique **Adicionar URI** e cole **exatamente**:

```text
msauth://com.onegallery.sync/pH93hJ0fsCAwFF5NxVPK0Snx6Ws%3D
```

4. **Configure** / **Salvar**.

Não use tipo **Web** nem **SPA** para esse `msauth://`.

O **AndroidManifest.xml** usa o path **decodificado** (`/pH93hJ0fs…=`) para receber o retorno do login; o Azure fica com a URI **codificada** acima.

### Se ainda disser “URI inválida”

- Apague URIs `http://localhost` ou `https://...` que você não usa.
- Tente também (alguns portais aceitam sem encoding):

```text
msauth://com.onegallery.sync/pH93hJ0fsCAwFF5NxVPK0Snx6Ws=
```

- Confirme o app suporta **contas pessoais Microsoft** (multitenant + contas pessoais).

---

## Caminho C — **Manifesto do aplicativo** (JSON)

1. **Gerenciar** → **Manifesto**.
2. Em `replyUrlsWithType` ou `publicClient`, inclua a URI `msauth://...` (como acima).
3. `"allowPublicClient": true` se existir e estiver `false`.
4. **Salvar**.

---

## Conferir no celular

No app → **Conectar OneDrive** → card **“Cole no Azure → Android”**: pacote, hash e redirect do **APK instalado** devem coincidir com o portal.

Hash de outro APK (compilado no seu PC):

```bash
apksigner verify --print-certs OneGallerySync-debug.apk | grep "SHA-1 digest"
# Converta o hex para Base64 (ou use o hash no app).
```

Registre **outra** URI (mobile/desktop ou outro Android) com esse hash.
