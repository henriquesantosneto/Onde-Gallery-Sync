package com.onegallery.sync.ui.gallery

import android.content.Context

enum class AlbumListSort(val label: String) {
    NAME_A_Z("Nome A → Z"),
    NAME_Z_A("Nome Z → A"),
    DATE_NEWEST("Mais recentes"),
}

class GalleryPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("onegallery_gallery", Context.MODE_PRIVATE)

    var sortOrder: GallerySortOrder
        get() = GallerySortOrder.entries.getOrElse(
            prefs.getInt(KEY_SORT, 0),
        ) { GallerySortOrder.DATE_NEWEST }
        set(value) = prefs.edit().putInt(KEY_SORT, value.ordinal).apply()

    var albumListSort: AlbumListSort
        get() = AlbumListSort.entries.getOrElse(
            prefs.getInt(KEY_ALBUM_SORT, 0),
        ) { AlbumListSort.NAME_A_Z }
        set(value) = prefs.edit().putInt(KEY_ALBUM_SORT, value.ordinal).apply()

    /** Pinça na grade de álbuns: maior = capas maiores (menos colunas). */
    var albumGridZoom: Float
        get() = prefs.getFloat(KEY_ALBUM_GRID_ZOOM, 1f)
        set(value) = prefs.edit().putFloat(
            KEY_ALBUM_GRID_ZOOM,
            value.coerceIn(GridPinchZoom.MIN_SCALE, GridPinchZoom.MAX_SCALE),
        ).apply()

    /** Pinça na galeria de fotos/vídeos. */
    var galleryGridZoom: Float
        get() = prefs.getFloat(KEY_GALLERY_GRID_ZOOM, 1f)
        set(value) = prefs.edit().putFloat(
            KEY_GALLERY_GRID_ZOOM,
            value.coerceIn(GridPinchZoom.MIN_SCALE, GridPinchZoom.MAX_SCALE),
        ).apply()

    /**
     * Abre mídia na rota experimental [MediaDetailLabScreen] em vez de [MediaDetailScreen].
     * Desligado por padrão — não altera o fluxo atual até você ativar em Ajustes.
     */
    var experimentalMediaDetailLab: Boolean
        get() = prefs.getBoolean(KEY_EXPERIMENTAL_MEDIA_LAB, false)
        set(value) = prefs.edit().putBoolean(KEY_EXPERIMENTAL_MEDIA_LAB, value).apply()

    /**
     * Grade / carrossel: oculta nuvem e vídeos sem JPEG no cache (menos decode na hora).
     * Fotos com URI local continuam visíveis.
     */
    var browseOnlyItemsWithThumbnail: Boolean
        get() = prefs.getBoolean(KEY_BROWSE_ONLY_WITH_THUMB, true)
        set(value) = prefs.edit().putBoolean(KEY_BROWSE_ONLY_WITH_THUMB, value).apply()

    /** Logcat tag OneGalleryPerf (prefetch, etc.) mesmo em release. */
    var logPerformanceTraces: Boolean
        get() = prefs.getBoolean(KEY_LOG_PERF, false)
        set(value) = prefs.edit().putBoolean(KEY_LOG_PERF, value).apply()

    companion object {
        private const val KEY_SORT = "sort_order"
        private const val KEY_ALBUM_SORT = "album_sort"
        private const val KEY_ALBUM_GRID_ZOOM = "album_grid_zoom"
        private const val KEY_GALLERY_GRID_ZOOM = "gallery_grid_zoom"
        private const val KEY_EXPERIMENTAL_MEDIA_LAB = "experimental_media_detail_lab"
        private const val KEY_BROWSE_ONLY_WITH_THUMB = "browse_only_items_with_thumbnail"
        private const val KEY_LOG_PERF = "log_performance_traces"
    }
}
