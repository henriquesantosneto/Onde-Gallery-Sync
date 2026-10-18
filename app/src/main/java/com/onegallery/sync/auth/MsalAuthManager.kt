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

data class AuthState(
    val isSignedIn: Boolean = false,
    val displayName: String? = null,
    val accountId: String? = null,
    val accessToken: String? = null,
    val lastError: String? = null,
)

class MsalAuthManager(context: Context) {
    private val appContext = context.applicationContext

    private val _state = MutableStateFlow(AuthState())
    val state: StateFlow<AuthState> = _state.asStateFlow()

    private var client: ISingleAccountPublicClientApplication? = null

    private val scopes: Array<String> = BuildConfig.GRAPH_SCOPES
        .split(' ')
        .filter { it.isNotBlank() }
        .toTypedArray()

    suspend fun initialize(): Result<Unit> = suspendCancellableCoroutine { cont ->
        PublicClientApplication.createSingleAccountPublicClientApplication(
            appContext,
            com.onegallery.sync.R.raw.auth_config,
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

    suspend fun signInInteractive(activity: Activity): Result<Unit> {
        val msal = client ?: return Result.failure(IllegalStateException("MSAL não inicializado"))
        return suspendCancellableCoroutine { cont ->
            val params = AcquireTokenParameters.Builder()
                .startAuthorizationFromActivity(activity)
                .withScopes(scopes.toList())
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
                        _state.value = _state.value.copy(lastError = exception.message)
                        if (cont.isActive) cont.resume(Result.failure(exception))
                    }

                    override fun onCancel() {
                        if (cont.isActive) {
                            cont.resume(Result.failure(IllegalStateException("Login cancelado")))
                        }
                    }
                })
                .build()
            msal.acquireToken(params)
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
