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
3. Plataforma **Android**:
   - Pacote: `com.onegallery.sync`
   - Assinatura debug (hash): ver `docs/MICROSOFT_APP_REGISTRATION.md`
4. Permissões Graph delegadas: `User.Read`, `Files.ReadWrite.All`, `offline_access`.
5. Copie o **Application (client) ID** para `msal.properties`:

```properties
MSAL_CLIENT_ID=seu-guid-aqui
```

6. Recompile: `./gradlew :app:assembleDebug`

Também funciona variável de ambiente `MSAL_CLIENT_ID` na hora do build.
