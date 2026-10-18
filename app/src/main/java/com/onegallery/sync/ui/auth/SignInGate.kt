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
import androidx.compose.material3.TextButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
    onLocalOnly: () -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var clientId by remember { mutableStateOf(authManager.savedClientId()) }
    var busy by remember { mutableStateOf(false) }
    var localError by remember { mutableStateOf<String?>(null) }
    val redirect = remember { authManager.redirectUriForAzure }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OneGallery Sync", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
        )
        Text(
            "1) Registre o app no Azure. 2) Cole o Client ID abaixo. 3) Use o redirect exibido no registro Android.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(bottom = 16.dp),
        )
        OutlinedTextField(
            value = clientId,
            onValueChange = { clientId = it.trim() },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Application (client) ID") },
            singleLine = true,
        )
        Text(
            "Redirect URI (copie para o Azure → Android):",
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.padding(top = 16.dp),
        )
        Text(
            redirect,
            style = MaterialTheme.typography.bodySmall,
            fontFamily = FontFamily.Monospace,
            modifier = Modifier.padding(top = 4.dp, bottom = 8.dp),
        )
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
                if (clientId.isBlank()) {
                    localError = "Informe o Client ID do Azure."
                    return@Button
                }
                busy = true
                localError = null
                authManager.saveClientId(clientId)
                scope.launch {
                    authManager.initialize()
                        .onSuccess { onClientConfigured() }
                        .onFailure { localError = it.message }
                    busy = false
                }
            },
            enabled = !busy,
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text("Salvar Client ID e continuar")
        }
        Button(
            onClick = {
                val hostActivity = context.findActivity() ?: return@Button
                scope.launch {
                    authManager.signInInteractive(hostActivity)
                        .onFailure { localError = it.message }
                }
            },
            enabled = !busy && authManager.savedClientId().isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        ) {
            Text(stringResource(R.string.auth_sign_in))
        }
        TextButton(
            onClick = onLocalOnly,
            modifier = Modifier.padding(top = 16.dp),
        ) {
            Text("Usar galeria local sem OneDrive (por enquanto)")
        }
    }
}
