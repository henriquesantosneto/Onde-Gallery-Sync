package com.onegallery.sync.data.onedrive

import com.squareup.moshi.Json

data class DriveItemCollection(
    val value: List<DriveItem> = emptyList(),
    @Json(name = "@odata.nextLink") val nextLink: String? = null,
    @Json(name = "@odata.deltaLink") val deltaLink: String? = null,
)

data class DriveItem(
    val id: String,
    val name: String,
    val size: Long?,
    val file: DriveFileFacet?,
    val folder: DriveFolderFacet?,
    val lastModifiedDateTime: String?,
    val parentReference: ParentReference?,
    val deleted: DeletedItemFacet? = null,
)

data class DeletedItemFacet(
    val state: String? = null,
)

data class DriveFileFacet(
    val mimeType: String?,
)

data class DriveFolderFacet(
    val childCount: Int? = null,
)

data class ParentReference(
    val path: String?,
)
