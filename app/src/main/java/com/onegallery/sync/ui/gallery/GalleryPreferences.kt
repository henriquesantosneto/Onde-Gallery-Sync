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

    companion object {
        private const val KEY_SORT = "sort_order"
        private const val KEY_ALBUM_SORT = "album_sort"
        private const val KEY_ALBUM_GRID_ZOOM = "album_grid_zoom"
        private const val KEY_GALLERY_GRID_ZOOM = "gallery_grid_zoom"
    }
}
