package com.onegallery.sync.data.local

import android.app.Activity
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/**
 * Encaminha pedidos de exclusão no MediaStore para a Activity (dialog do sistema no Android 11+).
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

    suspend fun requestDelete(context: Context, uris: List<Uri>): Boolean {
        val targets = uris.distinct()
        if (targets.isEmpty()) return true

        return when {
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.R -> {
                requestViaMediaStoreDialog(context, targets)
            }
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q -> {
                targets.all { uri -> deleteOneApi29(context, uri) }
            }
            else -> {
                targets.all { uri ->
                    context.contentResolver.delete(uri, null, null) > 0
                }
            }
        }
    }

    private suspend fun requestViaMediaStoreDialog(context: Context, uris: List<Uri>): Boolean {
        val launch = launchIntentSender
            ?: return uris.all { context.contentResolver.delete(it, null, null) > 0 }

        return suspendCancellableCoroutine { cont ->
            pendingResult = { cont.resume(it) }
            cont.invokeOnCancellation {
                if (pendingResult != null) {
                    pendingResult = null
                }
            }
            val pending = MediaStore.createDeleteRequest(context.contentResolver, uris)
            launch(IntentSenderRequest.Builder(pending.intentSender).build())
        }
    }

    private suspend fun deleteOneApi29(context: Context, uri: Uri): Boolean {
        return try {
            context.contentResolver.delete(uri, null, null) > 0
        } catch (security: SecurityException) {
            val recoverable = security as? android.app.RecoverableSecurityException
                ?: return false
            requestRecoverable(recoverable)
        }
    }

    private suspend fun requestRecoverable(
        exception: android.app.RecoverableSecurityException,
    ): Boolean {
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

    fun mapActivityResult(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK
}
