package com.onegallery.sync.data.onedrive

import com.squareup.moshi.Json

data class CreateUploadSessionRequest(
    val item: UploadSessionItem = UploadSessionItem(),
)

data class UploadSessionItem(
    @Json(name = "@microsoft.graph.conflictBehavior")
    val conflictBehavior: String = "replace",
)

data class UploadSessionResponse(
    val uploadUrl: String,
    val expirationDateTime: String?,
)

data class UploadSessionStatus(
    @Json(name = "nextExpectedRanges")
    val nextExpectedRanges: List<String>? = null,
)

/** Estado persistido para retomar upload Graph após interrupção. */
data class PersistedUploadSession(
    val canonicalPath: String,
    val drivePath: String,
    val uploadUrl: String,
    val expirationDateTime: String?,
    val fileSize: Long,
    val fileLastModified: Long,
)
