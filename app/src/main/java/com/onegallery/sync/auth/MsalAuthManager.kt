package com.onegallery.sync.auth

import android.app.Activity
import android.content.Context
import com.microsoft.identity.client.AcquireTokenParameters
import com.microsoft.identity.client.AcquireTokenSilentParameters
import com.microsoft.identity.client.AuthenticationCallback
import com.microsoft.identity.client.IAuthenticationResult
import com.microsoft.identity.client.IPublicClientApplication
import com.microsoft.identity.client.ISingleAccountPublicClientApplication
import com.microsoft.identity.client.PublicClientApplication
import com.microsoft.identity.client.exception.MsalException
import com.onegallery.sync.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AuthState(
    val isSignedIn: Boolean = false,
    val displayName: String? = null,
    val accountId: String? = null,
    val accessToken: String? = null,
    val lastError: String? = null,
)

class MsalAuthManager(
    context: Context,
    private val authPreferences: AuthPreferences,
) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private var client: ISingleAccountPublicClientApplication? = null
    private var configFile: File? = null

    val redirectUriForAzure: String
        get() = MsalConfigFactory.computeRedirectUri(appContext)

    val azureRegistrationHint: String
        get() {
            val hash = MsalConfigFactory.signatureHashBase64(appContext)
            val redirect = redirectUriForAzure
            val hashLine = hash ?: MsalRedirectUri.bakedSignatureHash().ifBlank { "—" }
            return buildString {
                appendLine("Pacote: ${appContext.packageName}")
                appendLine("Hash (Android no Azure): $hashLine")
                appendLine("URI (Mobile/desktop): $redirect")
                if (!MsalRedirectUri.runtimeMatchesBuild(hash)) {
                    appendLine()
                    append(
                        "Este APK foi reinstalado com outra assinatura ou é uma build diferente. " +
                            "Registre no Azure a URI acima (do hash deste aparelho), não a de outro APK.",
                    )
                }
            }
        }

    private fun enrichMsalError(message: String?, scopesAttempt: String? = null): String {
        val base = message?.trim().orEmpty()
        val lower = base.lowercase()
        val scopeLine = scopesAttempt?.let { "\nEscopos tentados: $it" }.orEmpty()
        val duplicateRedirectHint = if (lower.contains("more than one app") && lower.contains("browsertab")) {
            "\n\n— Conflito msauth —\n" +
                "Desinstale versões antigas do OneGallery Sync e instale só o APK mais recente. " +
                "O app não pode ter dois handlers msauth:// (erro corrigido na 0.5.10+)."
        } else {
            ""
        }
        val scopeHint = if (
            (lower.contains("scope") && lower.contains("declin")) ||
            lower.contains("invalid_scope") ||
            lower.contains("650053") ||
            lower.contains("650057")
        ) {
            "\n\n— Permissões Graph no Azure —$scopeLine\n" +
                "1. Registro → **Autenticação** → **Permitir fluxos de cliente público** = Sim.\n" +
                "2. Contas suportadas = **contas pessoais Microsoft** + trabalho.\n" +
                "3. **Permissões de APIs** → Microsoft Graph → **Delegadas** (não “Aplicativo”):\n" +
                "   • User.Read\n" +
                "   • Files.ReadWrite (**não** é o mesmo que Files.ReadWrite.All — adicione o que faltar)\n" +
                "4. Salvar. @outlook: aceite tudo na tela da Microsoft (não só no portal).\n" +
                "5. Se só tiver .All no portal, o app tenta de novo com Files.ReadWrite.All automaticamente."
        } else {
            ""
        }
        val redirectHint = if (
            lower.contains("90023") ||
            lower.contains("redirect") ||
            lower.contains("redirect_uri") ||
            lower.contains("reply address")
        ) {
            "\n\n— Azure —\n${azureRegistrationHint.trim()}\n\n" +
                "Em Autenticação → Mobile/desktop, cole a URI exata. " +
                "Se existir tile Android, use o hash com / e = (sem %2F)."
        } else {
            ""
        }
        return base + scopeLine + duplicateRedirectHint + scopeHint + redirectHint
    }

    private val scopes: Array<String> = BuildConfig.GRAPH_SCOPES
        .split(' ')
        .filter { it.isNotBlank() }
        .toTypedArray()

    val requestedScopesLabel: String
        get() = scopes.joinToString(", ")

    /** Tentativas se o portal só tiver .All ou só Files.ReadWrite configurado. */
    private val signInScopeAttempts: List<List<String>> by lazy {
        listOf(
            scopes.toList(),
            listOf("User.Read", "Files.ReadWrite.All"),
        ).distinctBy { it.joinToString(" ") }
    }

    fun savedClientId(): String = effectiveClientId()

    fun effectiveClientId(): String {
        val saved = authPreferences.azureClientId
        if (saved.isNotBlank() && !saved.startsWith("00000000")) return saved
        val baked = BuildConfig.MSAL_CLIENT_ID
        return if (baked.isNotBlank() && !baked.startsWith("00000000")) baked else saved
    }

    fun needsUserClientId(): Boolean = effectiveClientId().isBlank() || effectiveClientId().startsWith("00000000")

    fun saveClientId(clientId: String) {
        authPreferences.azureClientId = clientId
    }

    suspend fun initialize(): Result<Unit> {
        val clientId = effectiveClientId()
        if (clientId.isBlank() || clientId.startsWith("00000000")) {
            return Result.failure(
                IllegalStateException(
                    "Este APK não tem Client ID embutido. Quem compila precisa configurar msal.properties " +
                        "(ver docs/BUILTIN_MSAL.md). Você não precisa criar Azure — só entrar com Microsoft " +
                        "quando o APK estiver configurado.",
                ),
            )
        }
        configFile = MsalConfigFactory.writeConfigFile(appContext, clientId)
        client = null
        return suspendCancellableCoroutine { cont ->
            PublicClientApplication.createSingleAccountPublicClientApplication(
                appContext,
                configFile!!,
                object : IPublicClientApplication.ISingleAccountApplicationCreatedListener {
                    override fun onCreated(application: ISingleAccountPublicClientApplication) {
                        client = application
                        application.getCurrentAccountAsync(object :
                            ISingleAccountPublicClientApplication.CurrentAccountCallback {
                            override fun onAccountLoaded(activeAccount: com.microsoft.identity.client.IAccount?) {
                                if (activeAccount != null) {
                                    _state.value = AuthState(
                                        isSignedIn = true,
                                        displayName = activeAccount.username,
                                        accountId = activeAccount.id,
                                    )
                                }
                                if (cont.isActive) cont.resume(Result.success(Unit))
                            }

                            override fun onAccountChanged(
                                priorAccount: com.microsoft.identity.client.IAccount?,
                                currentAccount: com.microsoft.identity.client.IAccount?,
                            ) {
                                _state.value = if (currentAccount != null) {
                                    AuthState(
                                        isSignedIn = true,
                                        displayName = currentAccount.username,
                                        accountId = currentAccount.id,
                                    )
                                } else {
                                    AuthState()
                                }
                            }

                            override fun onError(exception: MsalException) {
                                if (cont.isActive) {
                                    cont.resume(Result.failure(exception))
                                }
                            }
                        })
                    }

                    override fun onError(exception: MsalException) {
                        if (cont.isActive) cont.resume(Result.failure(exception))
                    }
                },
            )
        }
    }

    suspend fun signInInteractive(activity: Activity): Result<Unit> {
        val msal = client ?: return Result.failure(IllegalStateException("MSAL não inicializado"))
        if (activity.isFinishing || activity.isDestroyed) {
            return Result.failure(IllegalStateException("Tela indisponível — abra o login de novo."))
        }
        var lastFailure: Result<Unit>? = null
        for (attemptScopes in signInScopeAttempts) {
            val result = acquireTokenInteractive(activity, msal, attemptScopes)
            if (result.isSuccess) return result
            lastFailure = result
            val msg = result.exceptionOrNull()?.message?.lowercase().orEmpty()
            val scopeDeclined = msg.contains("scope") && msg.contains("declin")
            if (!scopeDeclined) return result
        }
        return lastFailure ?: Result.failure(IllegalStateException("Login falhou"))
    }

    private suspend fun acquireTokenInteractive(
        activity: Activity,
        msal: ISingleAccountPublicClientApplication,
        attemptScopes: List<String>,
    ): Result<Unit> = withContext(Dispatchers.Main.immediate) {
        suspendCancellableCoroutine { cont ->
            val params = AcquireTokenParameters.Builder()
                .startAuthorizationFromActivity(activity)
                .withScopes(attemptScopes)
                .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        _state.value = AuthState(
                            isSignedIn = true,
                            displayName = authenticationResult.account.username,
                            accountId = authenticationResult.account.id,
                            accessToken = authenticationResult.accessToken,
                        )
                        if (cont.isActive) cont.resume(Result.success(Unit))
                    }

                    override fun onError(exception: MsalException) {
                        val msg = enrichMsalError(
                            exception.message,
                            attemptScopes.joinToString(", "),
                        )
                        _state.value = _state.value.copy(lastError = msg)
                        if (cont.isActive) {
                            cont.resume(Result.failure(IllegalStateException(msg, exception)))
                        }
                    }

                    override fun onCancel() {
                        if (cont.isActive) {
                            cont.resume(Result.failure(IllegalStateException("Login cancelado")))
                        }
                    }
                })
                .build()
            try {
                msal.acquireToken(params)
            } catch (t: Throwable) {
                val msg = enrichMsalError(t.message ?: t.javaClass.simpleName, attemptScopes.joinToString(", "))
                if (cont.isActive) cont.resume(Result.failure(IllegalStateException(msg, t)))
            }
        }
    }

    suspend fun acquireTokenSilent(): Result<String> {
        val msal = client ?: return Result.failure(IllegalStateException("MSAL não inicializado"))
        val account = msal.currentAccount?.currentAccount
            ?: return Result.failure(IllegalStateException("Nenhuma conta Microsoft ativa"))
        return suspendCancellableCoroutine { cont ->
            val params = AcquireTokenSilentParameters.Builder()
                .withScopes(scopes.toList())
                .forAccount(account)
                .fromAuthority(account.authority)
                .withCallback(object : AuthenticationCallback {
                    override fun onSuccess(authenticationResult: IAuthenticationResult) {
                        _state.value = _state.value.copy(
                            accessToken = authenticationResult.accessToken,
                            isSignedIn = true,
                            displayName = authenticationResult.account.username,
                            accountId = authenticationResult.account.id,
                        )
                        if (cont.isActive) cont.resume(Result.success(authenticationResult.accessToken))
                    }

                    override fun onError(exception: MsalException) {
                        _state.value = _state.value.copy(lastError = exception.message)
                        if (cont.isActive) cont.resume(Result.failure(exception))
                    }

                    override fun onCancel() {
                        if (cont.isActive) {
                            cont.resume(Result.failure(IllegalStateException("Token silencioso cancelado")))
                        }
                    }
                })
                .build()
            msal.acquireTokenSilent(params)
        }
    }

    suspend fun signOut(): Result<Unit> {
        val msal = client ?: return Result.success(Unit)
        return suspendCancellableCoroutine { cont ->
            msal.signOut(object : ISingleAccountPublicClientApplication.SignOutCallback {
                override fun onSignOut() {
                    _state.value = AuthState()
                    if (cont.isActive) cont.resume(Result.success(Unit))
                }

                override fun onError(exception: MsalException) {
                    if (cont.isActive) cont.resume(Result.failure(exception))
                }
            })
        }
    }
}
