package com.onegallery.sync.ui.gallery

import java.io.File
import java.util.concurrent.ConcurrentHashMap

/** Último JPEG válido por item — sobrevive a refresh da lista após rolagem. */
internal object GridVideoThumbPaintCache {
    private val lastPathByCanonical = ConcurrentHashMap<String, String>()

    fun remember(canonicalPath: String, file: File) {
        if (file.isFile && file.length() > 0L) {
            lastPathByCanonical[canonicalPath] = file.absolutePath
        }
    }

    fun recall(canonicalPath: String): File? {
        val path = lastPathByCanonical[canonicalPath] ?: return null
        val file = File(path)
        if (!file.isFile || file.length() <= 0L) {
            lastPathByCanonical.remove(canonicalPath, path)
            return null
        }
        return file
    }

    fun forget(canonicalPath: String) {
        lastPathByCanonical.remove(canonicalPath)
    }
}
