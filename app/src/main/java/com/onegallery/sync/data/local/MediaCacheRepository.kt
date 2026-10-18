package com.onegallery.sync.data.local

import android.content.Context
import java.io.File

class MediaCacheRepository(context: Context) {
    private val originalsDir = File(context.cacheDir, "originals").apply { mkdirs() }
    private val thumbsDir = File(context.cacheDir, "thumbs").apply { mkdirs() }

    fun originalFile(canonicalPath: String): File {
        val safe = canonicalPath.replace('/', '_').replace(':', '_')
        return File(originalsDir, safe)
    }

    fun thumbFile(canonicalPath: String): File {
        val safe = canonicalPath.replace('/', '_').replace(':', '_')
        return File(thumbsDir, safe)
    }

    fun hasOriginal(canonicalPath: String): Boolean = originalFile(canonicalPath).exists()

    fun deleteCached(canonicalPath: String) {
        originalFile(canonicalPath).delete()
        thumbFile(canonicalPath).delete()
    }

    fun deleteOriginalOnly(canonicalPath: String) {
        originalFile(canonicalPath).delete()
    }

    fun thumbnailIfCached(canonicalPath: String): File? =
        thumbFile(canonicalPath).takeIf { it.exists() && it.length() > 0L }
}
