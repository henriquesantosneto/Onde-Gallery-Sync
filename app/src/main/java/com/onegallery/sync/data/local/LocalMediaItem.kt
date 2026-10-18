package com.onegallery.sync.data.local

import android.net.Uri

enum class MediaKind {
    IMAGE,
    VIDEO,
}

data class LocalMediaItem(
    val id: Long,
    val uri: Uri,
    val displayName: String,
    val bucketDisplayName: String,
    val relativePath: String?,
    val dateAddedSeconds: Long,
    val sizeBytes: Long,
    val kind: MediaKind,
    val mimeType: String?,
)

data class MediaAlbum(
    val bucketId: String,
    val title: String,
    val itemCount: Int,
    val coverUri: Uri?,
)
