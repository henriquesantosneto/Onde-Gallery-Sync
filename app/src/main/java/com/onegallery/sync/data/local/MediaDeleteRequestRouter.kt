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
 * Exclusão no MediaStore: tentativa silenciosa primeiro; diálogo do sistema só quando necessário.
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

    /** Apaga o que o sistema permitir sem abrir diálogo. */
    fun deleteSilent(context: Context, uris: List<Uri>): Set<Uri> {
        return uris.filter { uri -> deleteUriSilent(context, uri) }.toSet()
    }

    /**
     * Pede consentimento quando necessário e exclui os URIs. Retorna false se cancelado ou falha ao apagar.
     */
    suspend fun requestDelete(context: Context, uris: List<Uri>): Boolean {
        val targets = uris.distinct()
        if (targets.isEmpty()) return true

        val silent = deleteSilent(context, targets)
        val remaining = targets.filter { it !in silent }
        if (remaining.isEmpty()) return true

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                deleteBatchApi30Plus(context, remaining)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                remaining.all { uri -> deleteOneApi29(context, uri) }
            }
            else -> {
                deleteUrisDirect(context, remaining)
            }
        }
    }

    private fun deleteUriSilent(context: Context, uri: Uri): Boolean {
        return runCatching { context.contentResolver.delete(uri, null, null) > 0 }
            .getOrDefault(false)
    }

    private suspend fun deleteBatchApi30Plus(context: Context, uris: List<Uri>): Boolean {
        val launch = launchIntentSender ?: return deleteUrisDirect(context, uris)

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
        if (deleteUriSilent(context, uri)) return true
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (security: SecurityException) {
            val recoverable = security as? RecoverableSecurityException ?: return false
            if (!requestRecoverable(recoverable)) return false
            context.contentResolver.delete(uri, null, null) > 0
        }
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
        return uris.all { deleteUriSilent(context, it) }
    }

    fun mapActivityResult(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK
}
