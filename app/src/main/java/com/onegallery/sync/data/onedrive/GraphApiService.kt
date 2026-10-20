package com.onegallery.sync.data.onedrive

import okhttp3.RequestBody
import okhttp3.ResponseBody
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query
import retrofit2.http.Streaming
import retrofit2.http.Url

interface GraphApiService {
    @GET("v1.0/me/drive/root")
    suspend fun getDriveRoot(
        @Header("Authorization") authorization: String,
    ): DriveItem

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

    @GET("v1.0/me/drive/items/{itemId}/delta")
    suspend fun listFolderDeltaInitial(
        @Header("Authorization") authorization: String,
        @Path("itemId") itemId: String,
        @Query("\$top") top: Int = 200,
    ): DriveItemCollection

    @GET
    suspend fun listDriveNext(
        @Url url: String,
        @Header("Authorization") authorization: String,
    ): DriveItemCollection

    @GET("v1.0/me/drive/root:/{itemPath}")
    suspend fun getItemByDrivePath(
        @Header("Authorization") authorization: String,
        @Path(value = "itemPath", encoded = true) itemPath: String,
    ): DriveItem

    @POST("v1.0/me/drive/root/children")
    suspend fun createFolderUnderDriveRoot(
        @Header("Authorization") authorization: String,
        @Body body: CreateFolderRequest,
    ): DriveItem

    @POST("v1.0/me/drive/items/{parentId}/children")
    suspend fun createFolderUnderParent(
        @Header("Authorization") authorization: String,
        @Path("parentId") parentId: String,
        @Body body: CreateFolderRequest,
    ): DriveItem

    @PUT("v1.0/me/drive/root:/{itemPath}:/content")
    suspend fun uploadSmallFile(
        @Header("Authorization") authorization: String,
        @Path(value = "itemPath", encoded = true) itemPath: String,
        @Body body: RequestBody,
    ): DriveItem

    @POST("v1.0/me/drive/root:/{itemPath}:/createUploadSession")
    suspend fun createUploadSession(
        @Header("Authorization") authorization: String,
        @Path(value = "itemPath", encoded = true) itemPath: String,
        @Body body: CreateUploadSessionRequest = CreateUploadSessionRequest(),
    ): UploadSessionResponse

    @GET("v1.0/me/drive/items/{itemId}")
    suspend fun getDriveItem(
        @Header("Authorization") authorization: String,
        @Path("itemId") itemId: String,
    ): DriveItem

    @GET("v1.0/me/drive/items/{itemId}/content")
    @Streaming
    suspend fun downloadItemContent(
        @Header("Authorization") authorization: String,
        @Path("itemId") itemId: String,
    ): ResponseBody

    @GET("v1.0/me/drive/items/{itemId}/thumbnails/0/large/content")
    @Streaming
    suspend fun downloadItemThumbnailLarge(
        @Header("Authorization") authorization: String,
        @Path("itemId") itemId: String,
    ): ResponseBody

    @GET("v1.0/me/drive/items/{itemId}/thumbnails/0/medium/content")
    @Streaming
    suspend fun downloadItemThumbnailMedium(
        @Header("Authorization") authorization: String,
        @Path("itemId") itemId: String,
    ): ResponseBody

    @DELETE("v1.0/me/drive/items/{itemId}")
    suspend fun deleteItem(
        @Header("Authorization") authorization: String,
        @Path("itemId") itemId: String,
    )
}

data class CreateFolderRequest(
    val name: String,
    val folder: DriveFolderFacet = DriveFolderFacet(),
    @com.squareup.moshi.Json(name = "@microsoft.graph.conflictBehavior")
    val conflictBehavior: String = "fail",
)
