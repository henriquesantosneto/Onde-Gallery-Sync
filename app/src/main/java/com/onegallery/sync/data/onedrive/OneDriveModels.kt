package com.onegallery.sync.data.onedrive

import com.squareup.moshi.Json

data class DriveItemCollection(
    val value: List<DriveItem> = emptyList(),
    @Json(name = "@odata.nextLink") val nextLink: String? = null,
    @Json(name = "@odata.deltaLink") val deltaLink: String? = null,
)

data class DriveItem(
    /** Delta pode trazer itens só com [deleted] (sem nome). */
    val id: String? = null,
    val name: String? = null,
    val size: Long?,
    val file: DriveFileFacet?,
    val folder: DriveFolderFacet?,
    val lastModifiedDateTime: String?,
    val parentReference: ParentReference?,
    val deleted: DeletedItemFacet? = null,
    @Json(name = "@removed") val removed: RemovedItemFacet? = null,
)

data class DeletedItemFacet(
    val state: String? = null,
)

/** Delta/tracking: item saiu do escopo ou foi apagado. */
data class RemovedItemFacet(
    val reason: String? = null,
)

data class DriveFileFacet(
    val mimeType: String?,
    val hashes: DriveFileHashes? = null,
)

data class DriveFileHashes(
    val quickXorHash: String? = null,
    val sha1Hash: String? = null,
)

data class DriveFolderFacet(
    val childCount: Int? = null,
)

data class ParentReference(
    val id: String? = null,
    val name: String? = null,
    val path: String?,
)

/** Resposta de `GET .../items/{id}/thumbnails` (URLs pré-assinadas + fallback /content). */
data class DriveItemThumbnailSetCollection(
    val value: List<DriveItemThumbnailSet> = emptyList(),
)

data class DriveItemThumbnailSet(
    val id: String? = null,
    val large: ThumbnailSize? = null,
    val medium: ThumbnailSize? = null,
    val small: ThumbnailSize? = null,
    val source: ThumbnailSize? = null,
)

data class ThumbnailSize(
    val width: Int? = null,
    val height: Int? = null,
    val url: String? = null,
)
