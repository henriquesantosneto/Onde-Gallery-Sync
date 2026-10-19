package com.onegallery.sync.ui.places

import android.graphics.Bitmap
import android.util.LruCache

/** Marcadores do mapa reutilizados entre aberturas da tela (evita decodificar tudo de novo). */
object MapMarkerBitmapCache {
    private const val MAX_ENTRIES = 512

    private val cache = object : LruCache<String, Bitmap>(MAX_ENTRIES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }

    private fun cacheKey(canonicalPath: String): String = "v2:$canonicalPath"

    fun get(canonicalPath: String): Bitmap? = cache.get(cacheKey(canonicalPath))

    fun put(canonicalPath: String, bitmap: Bitmap) {
        cache.put(cacheKey(canonicalPath), bitmap)
    }

    fun remove(canonicalPath: String) {
        cache.remove(cacheKey(canonicalPath))
    }
}
