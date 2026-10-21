package com.onegallery.sync.data.local

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MediaCacheRepository(context: Context) {
    private val originalsDir = File(context.cacheDir, "originals").apply { mkdirs() }
    private val thumbsDir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val thumbExistsCache = ConcurrentHashMap<String, Boolean>()

    /** Disparado após cada miniatura gravada (`canonicalPath` do item). */
    @Volatile
    var onThumbnailCommitted: ((canonicalPath: String) -> Unit)? = null

    fun originalFile(canonicalPath: String): File {
        val safe = safeFileName(canonicalPath)
        return File(originalsDir, safe)
    }

    fun originalPartFile(canonicalPath: String): File {
        return File(originalsDir, safeFileName(canonicalPath) + ".part")
    }

    fun originalPartMetaFile(canonicalPath: String): File {
        return File(originalsDir, safeFileName(canonicalPath) + ".part.meta")
    }

    private fun safeFileName(canonicalPath: String): String {
        return canonicalPath.replace('/', '_').replace(':', '_')
    }

    fun thumbFile(canonicalPath: String): File {
        val safe = safeFileName(canonicalPath)
        return File(thumbsDir, safe)
    }

    fun thumbStagingFile(canonicalPath: String): File {
        return File(thumbsDir, safeFileName(canonicalPath) + ".new")
    }

    fun thumbFileByRemoteItemId(oneDriveItemId: String): File {
        val safe = oneDriveItemId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(thumbsDir, "item_$safe")
    }

    /** Substitui a miniatura só quando a nova já foi gravada (evita “buraco” na grade). */
    fun commitThumbnail(
        stagingFile: File,
        canonicalPath: String,
        oneDriveItemId: String? = null,
    ): File? {
        if (!stagingFile.isFile || stagingFile.length() <= 0L) {
            stagingFile.delete()
            return null
        }
        val dest = thumbFile(canonicalPath)
        dest.parentFile?.mkdirs()
        if (dest.exists() && !dest.delete()) {
            stagingFile.inputStream().buffered().use { input ->
                dest.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            stagingFile.delete()
            return dest.takeIf { it.length() > 0L }
        }
        if (!stagingFile.renameTo(dest)) {
            stagingFile.inputStream().buffered().use { input ->
                dest.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            stagingFile.delete()
        }
        val committed = dest.takeIf { it.isFile && it.length() > 0L }
        if (committed != null) {
            thumbExistsCache[canonicalPath] = true
            oneDriveItemId?.takeIf { it.isNotBlank() }?.let { itemId ->
                mirrorThumbToRemoteItemAlias(committed, itemId)
            }
            onThumbnailCommitted?.invoke(canonicalPath)
        }
        return committed
    }

    private fun mirrorThumbToRemoteItemAlias(source: File, oneDriveItemId: String) {
        val alias = thumbFileByRemoteItemId(oneDriveItemId)
        runCatching {
            source.inputStream().buffered().use { input ->
                alias.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        }
    }

    fun hasOriginal(canonicalPath: String): Boolean {
        val file = originalFile(canonicalPath)
        return file.isFile && file.length() > 0L
    }

    fun originalIfCached(canonicalPath: String): File? =
        originalFile(canonicalPath).takeIf { it.isFile && it.length() > 0L }

    fun deleteCached(canonicalPath: String) {
        deleteOriginalTransferArtifacts(canonicalPath)
        thumbFile(canonicalPath).delete()
        thumbExistsCache.remove(canonicalPath)
    }

    fun deleteOriginalOnly(canonicalPath: String) {
        deleteOriginalTransferArtifacts(canonicalPath)
    }

    fun deleteOriginalTransferArtifacts(canonicalPath: String) {
        originalFile(canonicalPath).delete()
        originalPartFile(canonicalPath).delete()
        originalPartMetaFile(canonicalPath).delete()
    }

    fun thumbnailIfCached(canonicalPath: String, oneDriveItemId: String? = null): File? {
        val file = thumbFile(canonicalPath)
        if (file.isFile && file.length() > 0L) {
            thumbExistsCache[canonicalPath] = true
            return file
        }
        val itemId = oneDriveItemId?.takeIf { it.isNotBlank() } ?: run {
            thumbExistsCache.remove(canonicalPath)
            return null
        }
        val alias = thumbFileByRemoteItemId(itemId)
        if (!alias.isFile || alias.length() <= 0L) {
            thumbExistsCache.remove(canonicalPath)
            return null
        }
        runCatching {
            alias.inputStream().buffered().use { input ->
                file.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        }
        if (file.isFile && file.length() > 0L) {
            thumbExistsCache[canonicalPath] = true
            return file
        }
        thumbExistsCache.remove(canonicalPath)
        return null
    }

    /** Após recriar o índice (ex.: troca de pasta na nuvem). */
    fun clearAll() {
        originalsDir.listFiles()?.forEach { it.delete() }
        thumbsDir.listFiles()?.forEach { it.delete() }
        thumbExistsCache.clear()
    }
}
