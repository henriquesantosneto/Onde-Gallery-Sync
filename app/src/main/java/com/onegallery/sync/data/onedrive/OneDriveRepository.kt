package com.onegallery.sync.data.onedrive

import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.sync.SyncCapability
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory

class OneDriveRepository(
    private val authManager: MsalAuthManager,
) {
    private val graphApi: GraphApiService by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        val client = OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
        Retrofit.Builder()
            .baseUrl("https://graph.microsoft.com/")
            .client(client)
            .addConverterFactory(
                MoshiConverterFactory.create(
                    Moshi.Builder()
                        .add(KotlinJsonAdapterFactory())
                        .build(),
                ),
            )
            .build()
            .create(GraphApiService::class.java)
    }

    /**
     * Placeholder: lista a raiz do OneDrive quando houver token válido.
     * Upload/download e reconciliação bidirecional ainda não estão implementados.
     */
    suspend fun probeRootListing(): Result<Int> {
        if (!SyncCapability.bidirectionalSyncEnabled) {
            return Result.failure(
                UnsupportedOperationException(SyncCapability.NOT_ENABLED_MESSAGE),
            )
        }
        val tokenResult = authManager.acquireTokenSilent()
        val token = tokenResult.getOrElse { return Result.failure(it) }
        return runCatching {
            graphApi.listDriveRootChildren(authorization = "Bearer $token").value.size
        }
    }
}
