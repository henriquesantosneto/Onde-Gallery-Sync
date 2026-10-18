package com.onegallery.sync.ui.auth

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
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
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = "OneGallery Sync",
            style = MaterialTheme.typography.headlineMedium,
        )
        Text(
            text = "Galeria Android com espelho OneDrive. Configure o registro Microsoft antes do primeiro login.",
            modifier = Modifier.padding(top = 12.dp, bottom = 24.dp),
            style = MaterialTheme.typography.bodyMedium,
        )
        if (msalConfigurationError != null) {
            Text(
                text = "MSAL: $msalConfigurationError",
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 16.dp),
            )
        }
        Button(
            onClick = {
                val hostActivity = context.findActivity() ?: return@Button
                scope.launch {
                    authManager.signInInteractive(hostActivity)
                }
            },
            enabled = true,
        ) {
            Text(stringResource(R.string.auth_sign_in))
        }
    }
}
