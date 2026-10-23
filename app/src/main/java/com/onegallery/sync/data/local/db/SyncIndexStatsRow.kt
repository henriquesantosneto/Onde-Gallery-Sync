package com.onegallery.sync.data.local.db

import androidx.room.ColumnInfo

/** Contagens agregadas do índice — evita carregar `sync_metadata` inteiro na aba Sync. */
data class SyncIndexStatsRow(
    @ColumnInfo(name = "total") val total: Int,
    @ColumnInfo(name = "withLocal") val withLocal: Int,
    @ColumnInfo(name = "synced") val synced: Int,
    @ColumnInfo(name = "pendingUpload") val pendingUpload: Int,
    @ColumnInfo(name = "pendingDownload") val pendingDownload: Int,
    @ColumnInfo(name = "cloudOnly") val cloudOnly: Int,
    @ColumnInfo(name = "localOnly") val localOnly: Int,
    @ColumnInfo(name = "errors") val errors: Int,
    @ColumnInfo(name = "conflicts") val conflicts: Int,
    @ColumnInfo(name = "attention") val attention: Int,
    @ColumnInfo(name = "images") val images: Int,
    @ColumnInfo(name = "videos") val videos: Int,
    @ColumnInfo(name = "audio") val audio: Int,
    @ColumnInfo(name = "documents") val documents: Int,
    @ColumnInfo(name = "otherTypes") val otherTypes: Int,
)
