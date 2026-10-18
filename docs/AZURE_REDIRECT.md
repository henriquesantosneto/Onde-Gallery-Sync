# URI de redirecionamento (Android / MSAL)

## Valores do APK GitHub (`OneGallerySync-debug.apk`)

| | |
|--|--|
| **Pacote** | `com.onegallery.sync` |
| **Hash (campo assinatura Android)** | `XNT/U2cXeU6BS9HUWsT4MsU1wY4=` |
| **URI completa** | `msauth://com.onegallery.sync/XNT%2FU2cXeU6BS9HUWsT4MsU1wY4%3D` |

---

## Caminho A — aparece **Android** no portal

1. https://entra.microsoft.com/ (ou portal.azure.com) → **Aplicativos** → **Registros de aplicativo** → seu app.
2. **Gerenciar** → **Autenticação**.
3. **Adicionar URI de redirecionamento** ou **Adicionar uma plataforma** → tile **Android**.
4. Pacote: `com.onegallery.sync`  
   Hash: `XNT/U2cXeU6BS9HUWsT4MsU1wY4=` (com `/` e `=`, sem `%2F`).
5. **Configurar** / **Salvar**.

---

## Caminho B — **não tem Android** (comum com conta pessoal / UI nova)

Use **Aplicativos móveis e de desktop** (Mobile and desktop applications):

1. **Autenticação** → **Adicionar URI de redirecionamento** (ou **Adicionar uma plataforma**).
2. Escolha **Aplicativos móveis e de desktop** / **Mobile and desktop applications**.
3. Em **URIs de redirecionamento personalizados** / **Custom redirect URIs**, clique **Adicionar URI** e cole **exatamente**:

```text
msauth://com.onegallery.sync/XNT%2FU2cXeU6BS9HUWsT4MsU1wY4%3D
```

4. **Configure** / **Salvar**.

Não use tipo **Web** nem **SPA** para esse `msauth://`.

O **AndroidManifest.xml** do app usa o path **decodificado** (`/XNT/U2c…=`) para receber o retorno do login; o Azure fica com a URI **codificada** acima.

### Se ainda disser “URI inválida”

- Apague URIs `http://localhost` ou `https://...` que você não usa.
- Tente também (alguns portais aceitam sem encoding):

```text
msauth://com.onegallery.sync/XNT/U2cXeU6BS9HUWsT4MsU1wY4=
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
keytool -printcert -jarfile OneGallerySync-debug.apk | openssl sha1 -binary | openssl base64
```

Registre **outra** URI (mobile/desktop ou outro Android) com esse hash.
