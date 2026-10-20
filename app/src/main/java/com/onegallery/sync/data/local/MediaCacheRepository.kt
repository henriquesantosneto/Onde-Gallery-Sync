package com.onegallery.sync.data.local

import android.content.Context
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MediaCacheRepository(context: Context) {
    private val originalsDir = File(context.cacheDir, "originals").apply { mkdirs() }
    private val thumbsDir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val thumbExistsCache = ConcurrentHashMap<String, Boolean>()

    /** Disparado após cada miniatura gravada; o app faz debounce antes de refrescar capas. */
    @Volatile
    var onThumbnailCommitted: (() -> Unit)? = null

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

    /** Substitui a miniatura só quando a nova já foi gravada (evita “buraco” na grade). */
    fun commitThumbnail(stagingFile: File, canonicalPath: String): File? {
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
            onThumbnailCommitted?.invoke()
        }
        return committed
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

    fun thumbnailIfCached(canonicalPath: String): File? {
        thumbExistsCache[canonicalPath]?.let { known ->
            if (!known) return null
            val file = thumbFile(canonicalPath)
            return if (file.isFile && file.length() > 0L) file else {
                thumbExistsCache.remove(canonicalPath)
                null
            }
        }
        val file = thumbFile(canonicalPath)
        val ok = file.isFile && file.length() > 0L
        thumbExistsCache[canonicalPath] = ok
        return file.takeIf { ok }
    }

    /** Após recriar o índice (ex.: troca de pasta na nuvem). */
    fun clearAll() {
        originalsDir.listFiles()?.forEach { it.delete() }
        thumbsDir.listFiles()?.forEach { it.delete() }
        thumbExistsCache.clear()
    }
}
