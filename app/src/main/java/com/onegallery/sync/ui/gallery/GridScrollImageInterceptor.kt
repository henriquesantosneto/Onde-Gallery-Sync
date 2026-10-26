package com.onegallery.sync.ui.gallery

import coil.imageLoader
import coil.intercept.Interceptor
import coil.request.ImageResult
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

internal const val GRID_SCROLL_DEFER_KEY = "onegallery.gridScrollDefer"

/** Depois da rolagem, poucas decodificações por vez para não travar o frame em que o dedo solta. */
private val gridDecodeSlots = Semaphore(3)

/**
 * Miniatura que já está na memória segue direto.
 * As outras esperam a rolagem parar e entram aos poucos.
 */
internal class GridScrollImageInterceptor : Interceptor {
    override suspend fun intercept(chain: Interceptor.Chain): ImageResult {
        val request = chain.request
        if (request.parameters.value<Boolean>(GRID_SCROLL_DEFER_KEY) != true) {
            return chain.proceed(request)
        }
        val cacheKey = request.memoryCacheKey
        if (cacheKey != null &&
            request.context.imageLoader.memoryCache?.get(cacheKey) != null
        ) {
            return chain.proceed(request)
        }
        if (!GalleryGridScrollGate.isUserScrollingGrid) {
            return chain.proceed(request)
        }
        while (GalleryGridScrollGate.isUserScrollingGrid) {
            delay(16)
        }
        return gridDecodeSlots.withPermit {
            while (GalleryGridScrollGate.isUserScrollingGrid) {
                delay(16)
            }
            chain.proceed(request)
        }
    }
}
