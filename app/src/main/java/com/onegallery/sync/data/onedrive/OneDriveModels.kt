package com.onegallery.sync.data.onedrive

import com.squareup.moshi.Json

data class DriveItemCollection(
    val value: List<DriveItem> = emptyList(),
    @Json(name = "@odata.nextLink") val nextLink: String? = null,
)

data class DriveItem(
    val id: String,
    val name: String,
    val size: Long?,
    val file: DriveFileFacet?,
    val folder: DriveFolderFacet?,
    val lastModifiedDateTime: String?,
    val parentReference: ParentReference?,
)

data class DriveFileFacet(
    val mimeType: String?,
)

data class DriveFolderFacet(
    val childCount: Int?,
)

data class ParentReference(
    val path: String?,
)
