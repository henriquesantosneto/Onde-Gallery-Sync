package com.onegallery.sync.data.onedrive

import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Microsoft Graph — endpoints usados quando a sincronização estiver implementada.
 * Documentação: https://learn.microsoft.com/graph/api/resources/onedrive
 */
interface GraphApiService {
    @GET("v1.0/me/drive/root/children")
    suspend fun listDriveRootChildren(
        @Header("Authorization") authorization: String,
        @Query("\$top") top: Int = 200,
    ): DriveItemCollection

    @GET("v1.0/me/drive/items/{itemId}/children")
    suspend fun listFolderChildren(
        @Header("Authorization") authorization: String,
        @Path("itemId") itemId: String,
        @Query("\$top") top: Int = 200,
    ): DriveItemCollection
}
