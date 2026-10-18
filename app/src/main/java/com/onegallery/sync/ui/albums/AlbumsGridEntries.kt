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

fun buildAlbumsGridEntries(
    placeAlbums: List<MediaAlbum>,
    folderAlbums: List<MediaAlbum>,
): List<AlbumsGridEntry> {
    val entries = mutableListOf<AlbumsGridEntry>()
    if (placeAlbums.isNotEmpty()) {
        entries.add(
            AlbumsGridEntry.Header(
                AlbumsSectionHeader("section-places", "Locais", AlbumsSectionKind.PLACES),
            ),
        )
        placeAlbums.forEach { album ->
            entries.add(AlbumsGridEntry.Album(album, isPlace = true))
        }
    }
    if (folderAlbums.isNotEmpty()) {
        entries.add(
            AlbumsGridEntry.Header(
                AlbumsSectionHeader("section-folders", "Álbuns", AlbumsSectionKind.FOLDERS),
            ),
        )
        folderAlbums.forEach { album ->
            entries.add(AlbumsGridEntry.Album(album, isPlace = false))
        }
    }
    return entries
}
