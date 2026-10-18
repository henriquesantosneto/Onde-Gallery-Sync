# Registro Microsoft (Azure) para OneGallery Sync

Este app usa **MSAL** com contas **Microsoft pessoais** e **Azure AD**, e acessa o OneDrive via **Microsoft Graph** (`Files.ReadWrite.All`, `offline_access`, `User.Read`).

## 1. Criar o registro de aplicativo

1. Acesse [Portal do Azure](https://portal.azure.com/) → **Microsoft Entra ID** → **Registros de aplicativo** → **Novo registro**.
2. Nome: `OneGallery Sync` (ou outro de sua preferência).
3. Tipos de conta suportados: **Contas em qualquer diretório organizacional e contas pessoais da Microsoft**.
4. URI de redirecionamento: plataforma **Android**, pacote `com.onegallery.sync`.
5. Anote o **Application (client) ID**.

## 2. Assinatura do APK e hash MSAL

Para login interativo no Android, o MSAL exige um redirect no formato:

```text
msauth://com.onegallery.sync/<BASE64_URL_ENCODED_SIGNATURE_HASH>
```

Gere o hash da assinatura do keystore que você usará no APK (debug ou release):

```bash
keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore \
  | openssl sha1 -binary | openssl base64
```

Senha padrão do debug keystore: `android`.

Substituir em:

- `app/src/main/res/raw/auth_config.json` → `client_id` e `redirect_uri`
- `app/build.gradle.kts` → `MSAL_CLIENT_ID` e `MSAL_REDIRECT_URI`
- `app/src/main/AndroidManifest.xml` → `android:path` em `BrowserTabActivity`

## 3. Permissões Graph (delegadas)

Em **Permissões de API** → **Microsoft Graph** → **Permissões delegadas**:

| Permissão | Uso |
|-----------|-----|
| `User.Read` | Identidade da conta |
| `Files.ReadWrite.All` | Ler/escrever arquivos no OneDrive do usuário |
| `offline_access` | Refresh token para sync em segundo plano |

Conceda **consentimento de administrador** apenas se usar conta corporativa com políticas restritivas; contas pessoais em geral consentem no login.

## 4. APK privado no Samsung

1. Gere um keystore de release (guarde a senha com segurança).
2. Configure assinatura em Android Studio (**Build → Generate Signed Bundle / APK**).
3. Instale via USB (`adb install app-release.apk`) ou ferramentas Samsung (Smart Switch não é necessário para sideload).
4. Ative **Origens desconhecidas** / instalação do desenvolvedor conforme o modelo.

## 5. Estado atual deste repositório

- Login Microsoft e leitura da galeria local (**MediaStore**, incluindo pastas como WhatsApp quando visíveis ao sistema) estão preparados.
- **Sincronização bidirecional completa não está ativa** (`SyncCapability.bidirectionalSyncEnabled = false`).
- Não marque o produto como “sync completo” até implementar e testar upload, download sob demanda, exclusão com confirmação e resolução de conflitos.
