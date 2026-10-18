# Login Microsoft (estilo ES File Explorer)

Quem **usa** o APK **não** cria Azure. Quem **compila** o APK registra o app **uma vez** no portal Microsoft e embute o Client ID.

## Para quem usa o app

1. Toque **Conectar OneDrive (login Microsoft)**.
2. Entre com sua conta pessoal Microsoft.
3. Navegue nas pastas na nuvem e escolha onde sincronizar.

Se aparecer erro de configuração, o APK foi gerado sem Client ID — peça uma build com `msal.properties` preenchido.

## Para quem compila o APK (uma vez)

1. [Portal Azure](https://portal.azure.com/) → **Registros de aplicativo** → **Novo registro**.
2. Contas: **pessoais + organizacionais**.
3. Plataforma **Android** (valores do APK GitHub): [docs/AZURE_REDIRECT.md](AZURE_REDIRECT.md)
   - Pacote: `com.onegallery.sync`
   - Hash: veja o card no app (build CI: `pH93hJ0fsCAwFF5NxVPK0Snx6Ws=`)
4. **Permissões de APIs** → **Microsoft Graph** → **Delegadas** (obrigatório, senão “scopes declined”):
   - `User.Read`
   - `Files.ReadWrite` (OneDrive pessoal; **não** use só `Files.ReadWrite.All` em conta @outlook sem admin)
   - `offline_access`
5. **Autenticação** → Mobile/desktop → URI `msauth://…` (ver app ou [AZURE_REDIRECT.md](AZURE_REDIRECT.md)).
6. Copie o **Application (client) ID** para `msal.properties` (ou cole no app 0.5.5+):

```properties
MSAL_CLIENT_ID=seu-guid-aqui
```

7. Recompile: `./gradlew :app:assembleDebug`

Também funciona variável de ambiente `MSAL_CLIENT_ID` na hora do build.
