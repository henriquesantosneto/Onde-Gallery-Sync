package com.onegallery.sync.data.local

import android.content.Context
import java.io.File

class MediaCacheRepository(context: Context) {
    private val originalsDir = File(context.cacheDir, "originals").apply { mkdirs() }
    private val thumbsDir = File(context.cacheDir, "thumbs").apply { mkdirs() }

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
        return dest.takeIf { it.isFile && it.length() > 0L }
    }

    fun hasOriginal(canonicalPath: String): Boolean = originalFile(canonicalPath).exists()

    fun deleteCached(canonicalPath: String) {
        deleteOriginalTransferArtifacts(canonicalPath)
        thumbFile(canonicalPath).delete()
    }

    fun deleteOriginalOnly(canonicalPath: String) {
        deleteOriginalTransferArtifacts(canonicalPath)
    }

    fun deleteOriginalTransferArtifacts(canonicalPath: String) {
        originalFile(canonicalPath).delete()
        originalPartFile(canonicalPath).delete()
        originalPartMetaFile(canonicalPath).delete()
    }

    fun thumbnailIfCached(canonicalPath: String): File? =
        thumbFile(canonicalPath).takeIf { it.exists() && it.length() > 0L }

    /** Após recriar o índice (ex.: troca de pasta na nuvem). */
    fun clearAll() {
        originalsDir.listFiles()?.forEach { it.delete() }
        thumbsDir.listFiles()?.forEach { it.delete() }
    }
}
