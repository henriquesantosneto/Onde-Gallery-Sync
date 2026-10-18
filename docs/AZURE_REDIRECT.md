# URI de redirecionamento (Android / MSAL)

## APK baixado do GitHub (`OneGallerySync-debug.apk`)

Use estes valores no portal **Exatamente**:

| Campo | Valor |
|--------|--------|
| Plataforma | **Android** (não Web) |
| Nome do pacote | `com.onegallery.sync` |
| Hash da assinatura | `XNT/U2cXeU6BS9HUWsT4MsU1wY4=` |

URI completa equivalente:

```text
msauth://com.onegallery.sync/XNT%2FU2cXeU6BS9HUWsT4MsU1wY4%3D
```

No formulário Android do Azure, preencha **pacote + hash** — não cole a URI em “Web”.

### Se o portal disser que a URI é inválida

1. Remova redirects **Web** ou **SPA** com `msauth://` (só servem Android).
2. Em **Autenticação** → **Adicionar plataforma** → **Android** (não “Aplicativo móvel e desktop” com URI manual, se der erro).
3. O hash inclui **`/`** e **`=`** — no campo **hash** use o texto **cru** acima, sem `%2F` nem `%3D`.
4. Se você compilou o APK **no seu PC**, o hash pode ser outro. Abra o app → login → **Sou desenvolvedor** e copie o redirect exibido.

### Vários APKs (debug local + GitHub)

No mesmo registro, adicione **mais de uma** plataforma Android com o **mesmo pacote** e **hashes diferentes**, por exemplo:

- GitHub/cloud: `XNT/U2cXeU6BS9HUWsT4MsU1wY4=`
- Debug keystore local (`~/.android/debug.keystore`): `pH93hJ0fsCAwFF5NxVPK0Snx6Ws=`

Gerar hash do APK instalado:

```bash
keytool -printcert -jarfile OneGallerySync-debug.apk | openssl sha1 -binary | openssl base64
```
