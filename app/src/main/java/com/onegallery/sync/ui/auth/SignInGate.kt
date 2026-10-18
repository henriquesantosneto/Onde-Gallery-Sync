package com.onegallery.sync.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.onegallery.sync.BuildConfig
import com.onegallery.sync.R
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.auth.MsalBuiltInConfig
import com.onegallery.sync.auth.MsalConfigFactory
import kotlinx.coroutines.launch

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

@Composable
fun SignInGate(
    authManager: MsalAuthManager,
    msalConfigurationError: String?,
    onClientConfigured: () -> Unit,
    onBack: () -> Unit,
    onSignedIn: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clientId by remember { mutableStateOf(authManager.savedClientId()) }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    var showDevClientId by remember { mutableStateOf(false) }
    val redirect = remember { authManager.redirectUriForAzure }
    val signatureHash = remember { MsalConfigFactory.signatureHashBase64(context) }
    val packageName = remember { context.packageName }
    val needsClientId = authManager.needsUserClientId()
    val embedded = MsalBuiltInConfig.isEmbeddedClientConfigured()

    LaunchedEffect(embedded) {
        if (embedded) {
            authManager.initialize()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OneDrive na nuvem", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Text(
            "Igual ES File Explorer: entre com sua conta Microsoft. " +
                "Em seguida você navega e escolhe a pasta do OneDrive para a galeria.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        if (needsClientId && !showDevClientId) {
            Text(
                "Este APK não foi compilado com Client ID. Use o modo Pictures na tela anterior, " +
                    "ou peça ao desenvolvedor uma build com msal.properties.",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 12.dp),
            )
            TextButton(onClick = { showDevClientId = true }) {
                Text("Sou desenvolvedor — colar Client ID")
            }
        }
        Card(
            modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
            colors = androidx.compose.material3.CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("Cole no Azure → Android", style = MaterialTheme.typography.labelLarge)
                Text("Pacote: $packageName", style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                Text(
                    "Hash assinatura: ${signatureHash ?: "—"}",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Redirect (referência): $redirect",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.padding(top = 4.dp),
                )
                Text(
                    "Use plataforma Android, não Web. Hash com / e = vai cru no campo assinatura.",
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
        if (needsClientId && showDevClientId) {
            OutlinedTextField(
                value = clientId,
                onValueChange = { clientId = it.trim() },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Application (client) ID") },
                singleLine = true,
            )
            Text(
                "Redirect URI (Android no Azure):",
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(top = 16.dp),
            )
            Text(
                redirect,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
            )
            Button(
                onClick = {
                    if (clientId.isBlank()) {
                        localError = "Informe o Client ID."
                        return@Button
                    }
                    busy = true
                    authManager.saveClientId(clientId)
                    scope.launch {
                        authManager.initialize()
                            .onSuccess { onClientConfigured() }
                            .onFailure { localError = it.message }
                        busy = false
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Salvar Client ID")
            }
        }
        val error = localError ?: msalConfigurationError
        if (error != null) {
            Text(
                text = error,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }
        Button(
            onClick = {
                val hostActivity = context.findActivity() ?: return@Button
                busy = true
                localError = null
                scope.launch {
                    if (authManager.needsUserClientId() && clientId.isNotBlank()) {
                        authManager.saveClientId(clientId)
                    }
                    authManager.initialize()
                        .onFailure {
                            localError = it.message
                            busy = false
                            return@launch
                        }
                    authManager.signInInteractive(hostActivity)
                        .onSuccess { onSignedIn() }
                        .onFailure { localError = it.message }
                    busy = false
                }
            },
            enabled = !busy && (!needsClientId || clientId.isNotBlank() || authManager.savedClientId().isNotBlank()),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.auth_sign_in))
        }
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 16.dp)) {
            Text("Voltar")
        }
    }
}
