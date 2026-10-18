package com.onegallery.sync.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.onegallery.sync.R
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.sync.SyncPolicy
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(authManager: MsalAuthManager) {
    val authState by authManager.state.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = { TopAppBar(title = { Text("Ajustes") }) },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            Text("Conta", style = MaterialTheme.typography.titleMedium)
            Text(
                authState.displayName ?: "—",
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            Text("Pasta raiz no OneDrive", style = MaterialTheme.typography.titleMedium)
            Text(
                SyncPolicy.ONEDRIVE_MIRROR_ROOT,
                modifier = Modifier.padding(top = 4.dp, bottom = 16.dp),
            )
            Text(
                "Exclusões: lixeira ignorada na indexação; exclusões na nuvem exigirão confirmação antes de propagar (quando implementado).",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Button(
                onClick = { scope.launch { authManager.signOut() } },
                modifier = Modifier.padding(top = 24.dp),
            ) {
                Text(stringResource(R.string.auth_sign_out))
            }
        }
    }
}
