package com.onegallery.sync.ui.albums

import com.onegallery.sync.data.local.MediaAlbum

enum class AlbumsSectionKind {
    PLACES,
    FOLDERS,
}

data class AlbumsSectionHeader(
    val id: String,
    val title: String,
    val kind: AlbumsSectionKind,
)

sealed class AlbumsGridEntry(open val key: String) {
    data class Header(val header: AlbumsSectionHeader) : AlbumsGridEntry(header.id)
    data class Album(val album: MediaAlbum, val isPlace: Boolean) : AlbumsGridEntry(
        if (isPlace) "place-entry-${album.bucketId}" else "album-entry-${album.bucketId}",
    )
}

fun buildAlbumGridEntries(folderAlbums: List<MediaAlbum>): List<AlbumsGridEntry> {
    return folderAlbums.map { AlbumsGridEntry.Album(it, isPlace = false) }
}

fun buildPlaceGridEntries(placeAlbums: List<MediaAlbum>): List<AlbumsGridEntry> {
    return placeAlbums.map { AlbumsGridEntry.Album(it, isPlace = true) }
}
