package com.onegallery.sync.ui.welcome

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.onegallery.sync.BuildConfig
import com.onegallery.sync.auth.MsalBuiltInConfig
import com.onegallery.sync.sync.SyncCoordinator

@Composable
fun WelcomeScreen(
    syncCoordinator: SyncCoordinator,
    onFinished: () -> Unit,
    onConnectOneDriveCloud: () -> Unit,
) {
    val msalReady = MsalBuiltInConfig.isEmbeddedClientConfigured()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("OneGallery Sync", style = MaterialTheme.typography.headlineMedium)
        Text(
            "Versão ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            modifier = Modifier.padding(bottom = 16.dp),
        )
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 20.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    "OneDrive na nuvem",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    "Entre com sua conta Microsoft, escolha a pasta no OneDrive e sincronize " +
                        "fotos e vídeos com a galeria do aparelho.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        }
        Button(
            onClick = onConnectOneDriveCloud,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Conectar OneDrive (login Microsoft)")
        }
        if (!msalReady) {
            Text(
                "Configure o Client ID do Azure na próxima tela.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        OutlinedButton(
            onClick = {
                syncCoordinator.useLocalOnlyMode()
                onFinished()
            },
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text("Só galeria local (sem nuvem)")
        }
    }
}
