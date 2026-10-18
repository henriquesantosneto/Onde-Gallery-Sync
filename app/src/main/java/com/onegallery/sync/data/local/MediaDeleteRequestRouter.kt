package com.onegallery.sync.data.local

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.IntentSenderRequest
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed class MediaDeleteResult {
    data object Success : MediaDeleteResult()
    data object NothingToDelete : MediaDeleteResult()
    data object UserDenied : MediaDeleteResult()
    data class Failed(val remaining: List<Uri>) : MediaDeleteResult()
}

/**
 * Exclusão no MediaStore: tentativa silenciosa primeiro; diálogo do sistema só quando necessário.
 */
class MediaDeleteRequestRouter {
    private var launchIntentSender: ((IntentSenderRequest) -> Unit)? = null
    private var pendingResult: ((Boolean) -> Unit)? = null
    private val deleteMutex = Mutex()

    fun attachIntentSenderLauncher(launch: (IntentSenderRequest) -> Unit) {
        launchIntentSender = launch
    }

    fun onIntentSenderResult(success: Boolean) {
        pendingResult?.invoke(success)
        pendingResult = null
    }

    fun uriExists(context: Context, uri: Uri): Boolean {
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(MediaStore.MediaColumns._ID),
                null,
                null,
                null,
            )?.use { it.moveToFirst() } == true
        }.getOrDefault(false)
    }

    /** Apaga o que o sistema permitir sem abrir diálogo. Considera sucesso se o URI já não existir. */
    fun deleteSilent(context: Context, uris: List<Uri>): Set<Uri> {
        return uris.filter { uri -> isRemovedOrAbsent(context, uri) }.toSet()
    }

    suspend fun requestDelete(context: Context, uris: List<Uri>): MediaDeleteResult {
        return deleteMutex.withLock {
            requestDeleteLocked(context, uris.distinct())
        }
    }

    private suspend fun requestDeleteLocked(context: Context, targets: List<Uri>): MediaDeleteResult {
        if (targets.isEmpty()) return MediaDeleteResult.NothingToDelete

        val needWork = targets.filter { uriExists(context, it) }
        if (needWork.isEmpty()) return MediaDeleteResult.Success

        val silentRemoved = deleteSilent(context, needWork)
        val remaining = needWork.filter { it !in silentRemoved }
        if (remaining.isEmpty()) return MediaDeleteResult.Success

        val interactiveOk = when {
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
        if (!interactiveOk) return MediaDeleteResult.UserDenied

        val stillThere = remaining.filter { uriExists(context, it) }
        return if (stillThere.isEmpty()) {
            MediaDeleteResult.Success
        } else {
            MediaDeleteResult.Failed(stillThere)
        }
    }

    private fun isRemovedOrAbsent(context: Context, uri: Uri): Boolean {
        if (!uriExists(context, uri)) return true
        return deleteUriSilent(context, uri) || !uriExists(context, uri)
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
        deleteUrisDirect(context, uris)
        return true
    }

    private suspend fun deleteOneApi29(context: Context, uri: Uri): Boolean {
        if (isRemovedOrAbsent(context, uri)) return true
        return try {
            context.contentResolver.delete(uri, null, null) > 0 || !uriExists(context, uri)
        } catch (security: SecurityException) {
            val recoverable = security as? RecoverableSecurityException ?: return false
            if (!requestRecoverable(recoverable)) return false
            context.contentResolver.delete(uri, null, null) > 0 || !uriExists(context, uri)
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
        uris.forEach { isRemovedOrAbsent(context, it) }
        return uris.none { uriExists(context, it) }
    }

    fun mapActivityResult(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK
}
