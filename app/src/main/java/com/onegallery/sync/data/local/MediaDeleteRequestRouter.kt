package com.onegallery.sync.data.local

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Pedido de exclusão no MediaStore: um diálogo do sistema (Android 11+) e depois [ContentResolver.delete].
 */
class MediaDeleteRequestRouter {
    private var launchIntentSender: ((IntentSenderRequest) -> Unit)? = null
    private var pendingResult: ((Boolean) -> Unit)? = null

    fun attachIntentSenderLauncher(launch: (IntentSenderRequest) -> Unit) {
        launchIntentSender = launch
    }

    fun onIntentSenderResult(success: Boolean) {
        pendingResult?.invoke(success)
        pendingResult = null
    }

    /**
     * Pede consentimento quando necessário e exclui os URIs. Retorna false se cancelado ou falha ao apagar.
     */
    suspend fun requestDelete(context: Context, uris: List<Uri>): Boolean {
        val targets = uris.distinct()
        if (targets.isEmpty()) return true

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                deleteBatchApi30Plus(context, targets)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                targets.all { uri -> deleteOneApi29(context, uri) }
            }
            else -> {
                deleteUrisDirect(context, targets)
            }
        }
    }

    private suspend fun deleteBatchApi30Plus(context: Context, uris: List<Uri>): Boolean {
        if (deleteUrisDirect(context, uris)) return true

        val launch = launchIntentSender ?: return false
        val approved = suspendCancellableCoroutine { cont ->
            pendingResult = { cont.resume(it) }
            cont.invokeOnCancellation {
                if (pendingResult != null) {
                    pendingResult = null
                }
            }
            val pending = MediaStore.createDeleteRequest(context.contentResolver, uris)
            launch(IntentSenderRequest.Builder(pending.intentSender).build())
        }
        if (!approved) return false
        return deleteUrisDirect(context, uris)
    }

    private suspend fun deleteOneApi29(context: Context, uri: Uri): Boolean {
        try {
            if (context.contentResolver.delete(uri, null, null) > 0) return true
        } catch (security: SecurityException) {
            val recoverable = security as? RecoverableSecurityException ?: return false
            if (!requestRecoverable(recoverable)) return false
            return context.contentResolver.delete(uri, null, null) > 0
        }
        return false
    }

    private suspend fun requestRecoverable(exception: RecoverableSecurityException): Boolean {
        val launch = launchIntentSender ?: return false
        return suspendCancellableCoroutine { cont ->
            pendingResult = { cont.resume(it) }
            cont.invokeOnCancellation {
                if (pendingResult != null) {
                    pendingResult = null
                }
            }
            launch(
                IntentSenderRequest.Builder(exception.userAction.actionIntent.intentSender).build(),
            )
        }
    }

    private fun deleteUrisDirect(context: Context, uris: List<Uri>): Boolean {
        return uris.all { uri ->
            runCatching { context.contentResolver.delete(uri, null, null) > 0 }
                .getOrDefault(false)
        }
    }

    fun mapActivityResult(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK
}
