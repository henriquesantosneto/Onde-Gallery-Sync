# Permissões Azure (scopes declined)

Se aparecer **“scopes have been declined by the Server”**, quase sempre falta **configuração no registro**, não no celular.

## Checklist (Entra / Azure Portal)

1. **Registros de aplicativo** → seu app → **Autenticação**
   - **Permitir fluxos de cliente público** → **Sim**
   - Contas: **Qualquer diretório organizacional e contas pessoais Microsoft**

2. **Permissões de APIs** → **Adicionar permissão** → **Microsoft Graph** → **Permissões delegadas**
   - `User.Read`
   - `Files.ReadWrite` — leia/grava **OneDrive do usuário** (escopo que o app pede primeiro)
   - Opcional: `Files.ReadWrite.All` — **nome diferente**; se só esta estiver no portal, o app 0.5.11+ tenta de novo com ela

   Não marque só permissões **de aplicativo** (application) para este fluxo.

3. **Autenticação** → URI Mobile/desktop: ver [AZURE_REDIRECT.md](AZURE_REDIRECT.md)

4. No celular: ao logar, aceite **todas** as permissões na página da Microsoft.

## O app **não** pede mais `offline_access` no texto de escopos (evita recusa se não estiver no portal).

Escopos na build 0.5.11+: `User.Read`, `Files.ReadWrite`.
