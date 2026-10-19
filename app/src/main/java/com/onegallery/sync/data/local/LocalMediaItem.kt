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
    /** MediaStore DATE_TAKEN (segundos UTC), quando disponível. */
    val dateTakenSeconds: Long? = null,
    /** MediaStore LATITUDE/LONGITUDE (imagens; requer ACCESS_MEDIA_LOCATION). */
    val latitude: Double? = null,
    val longitude: Double? = null,
    /** MediaStore DATE_MODIFIED (segundos UTC). Usado na reconciliação com a nuvem. */
    val dateModifiedSeconds: Long,
    val sizeBytes: Long,
    val kind: MediaKind,
    val mimeType: String?,
    /** MediaStore DURATION (ms), só vídeos. */
    val durationMs: Long? = null,
)

data class MediaAlbum(
    val bucketId: String,
    val title: String,
    val itemCount: Int,
    val photoCount: Int = itemCount,
    val videoCount: Int = 0,
    val coverUri: Uri?,
    val sortTimestamp: Long = 0L,
    /** Álbum criado pelo usuário (pode renomear/excluir a organização). */
    val isUserManaged: Boolean = false,
    val userAlbumId: String? = null,
    val createdAtEpoch: Long? = null,
)
