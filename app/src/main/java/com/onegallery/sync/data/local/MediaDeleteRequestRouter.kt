package com.onegallery.sync.data.local

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.Context
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import android.os.Environment
import androidx.activity.result.IntentSenderRequest
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
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

    /** Segundo plano com MANAGE_EXTERNAL_STORAGE — sem diálogo do MediaStore. */
    fun deleteWithAllFilesAccessIfGranted(context: Context, uris: List<Uri>): Set<Uri> {
        if (!MediaStorageAccess.hasAllFilesAccess(context)) return emptySet()
        if (uris.isEmpty()) return emptySet()
        deleteWithAllFilesAccess(context, uris.distinct())
        return uris.filter { !uriExists(context, it) }.toSet()
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
        var remaining = needWork.filter { it !in silentRemoved }
        if (remaining.isEmpty()) return MediaDeleteResult.Success

        if (MediaStorageAccess.hasAllFilesAccess(context)) {
            deleteWithAllFilesAccess(context, remaining)
            remaining = remaining.filter { uriExists(context, it) }
            if (remaining.isEmpty()) return MediaDeleteResult.Success
        }

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

        val approved = withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
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
        return withContext(Dispatchers.Main.immediate) {
            suspendCancellableCoroutine { cont ->
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
    }

    private fun deleteUrisDirect(context: Context, uris: List<Uri>): Boolean {
        uris.forEach { isRemovedOrAbsent(context, it) }
        return uris.none { uriExists(context, it) }
    }

    private fun deleteWithAllFilesAccess(context: Context, uris: List<Uri>) {
        uris.forEach { uri ->
            if (!uriExists(context, uri)) return@forEach
            resolveMediaFile(context, uri)?.let { file ->
                runCatching {
                    if (file.isFile) file.delete()
                    file.parentFile?.listFiles()?.isEmpty()?.let { empty ->
                        if (empty) file.parentFile?.delete()
                    }
                }
            }
            deleteUriSilent(context, uri)
            if (uriExists(context, uri)) {
                runCatching { context.contentResolver.delete(uri, null, null) }
            }
        }
    }

    private fun resolveMediaFile(context: Context, uri: Uri): File? {
        val projection = buildList {
            add(MediaStore.MediaColumns.DISPLAY_NAME)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                add(MediaStore.MediaColumns.RELATIVE_PATH)
            } else {
                add(MediaStore.MediaColumns.DATA)
            }
        }.toTypedArray()
        return runCatching {
            context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
                if (!cursor.moveToFirst()) return null
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val relIdx = cursor.getColumnIndex(MediaStore.MediaColumns.RELATIVE_PATH)
                    val nameIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DISPLAY_NAME)
                    if (relIdx < 0 || nameIdx < 0) return null
                    val rel = cursor.getString(relIdx)?.trim('/') ?: return null
                    val name = cursor.getString(nameIdx) ?: return null
                    Environment.getExternalStorageDirectory().resolve("$rel/$name")
                } else {
                    val dataIdx = cursor.getColumnIndex(MediaStore.MediaColumns.DATA)
                    if (dataIdx < 0) return null
                    cursor.getString(dataIdx)?.let { File(it) }
                }
            }
        }.getOrNull()
    }

    fun mapActivityResult(resultCode: Int): Boolean = resultCode == Activity.RESULT_OK
}
