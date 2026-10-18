package com.onegallery.sync.data.onedrive

import android.content.Context
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.sync.SyncPaths
import com.onegallery.sync.sync.SyncPolicy
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.asRequestBody
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.HttpException
import retrofit2.Retrofit
import retrofit2.converter.moshi.MoshiConverterFactory
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.io.InputStream
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.ArrayDeque

data class RemoteDriveFile(
    val itemId: String,
    val name: String,
    val canonicalPath: String,
    val modifiedAtEpoch: Long?,
    val sizeBytes: Long?,
)

data class OneDriveBrowseEntry(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val childCount: Int?,
)

class OneDriveRepository(
    context: Context,
    private val authManager: MsalAuthManager,
    private val graphFolderPreferences: GraphFolderPreferences,
) {
    private val uploadSessionStore = UploadSessionStore(context)
    private val resumableTransfer by lazy {
        GraphResumableTransfer(graphApi, uploadSessionStore, graphHttpClient)
    }

    private val graphHttpClient: OkHttpClient by lazy {
        val logging = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        }
        OkHttpClient.Builder()
            .addInterceptor(logging)
            .build()
    }

    private val graphApi: GraphApiService by lazy {
        Retrofit.Builder()
            .baseUrl("https://graph.microsoft.com/")
            .client(graphHttpClient)
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

    suspend fun accessToken(): Result<String> {
        return authManager.accessTokenForGraph()
    }

    fun syncRootLabel(): String? = graphFolderPreferences.displayLabel

    fun isSyncRootConfigured(): Boolean = graphFolderPreferences.isConfigured()

    fun setSyncRoot(itemId: String, driveRelativePath: String, label: String) {
        graphFolderPreferences.setSyncRoot(itemId, driveRelativePath, label)
    }

    suspend fun getDriveRootId(): Result<String> {
        return runWithToken { token ->
            graphApi.getDriveRoot("Bearer $token").id
        }
    }

    suspend fun listBrowseChildren(folderId: String): Result<List<OneDriveBrowseEntry>> {
        return runWithToken { token ->
            val page = graphApi.listFolderChildren("Bearer $token", folderId)
            page.value.map { item ->
                OneDriveBrowseEntry(
                    id = item.id,
                    name = item.name,
                    isFolder = item.folder != null,
                    childCount = item.folder?.childCount,
                )
            }.filter { it.isFolder }
                .sortedBy { it.name.lowercase() }
        }
    }

    suspend fun createSubfolder(parentId: String, name: String): Result<OneDriveBrowseEntry> {
        return runWithToken { token ->
            val created = graphApi.createFolderUnderParent(
                authorization = "Bearer $token",
                parentId = parentId,
                body = CreateFolderRequest(name = name),
            )
            OneDriveBrowseEntry(
                id = created.id,
                name = created.name,
                isFolder = true,
                childCount = 0,
            )
        }
    }

    suspend fun ensureMirrorRootFolder(): Result<String> {
        if (graphFolderPreferences.isConfigured()) {
            return Result.success(graphFolderPreferences.syncRootItemId!!)
        }
        return runWithToken { token ->
            val rootPath = encodeDrivePath(SyncPolicy.ONEDRIVE_MIRROR_ROOT)
            try {
                graphApi.getItemByDrivePath("Bearer $token", rootPath).id
            } catch (http: HttpException) {
                if (http.code() != 404) throw http
                graphApi.createFolderUnderDriveRoot(
                    authorization = "Bearer $token",
                    body = CreateFolderRequest(name = SyncPolicy.ONEDRIVE_MIRROR_ROOT),
                ).id
            }.also { id ->
                graphFolderPreferences.setSyncRoot(
                    itemId = id,
                    driveRelativePath = SyncPolicy.ONEDRIVE_MIRROR_ROOT,
                    label = SyncPolicy.ONEDRIVE_MIRROR_ROOT,
                )
            }
        }
    }

    suspend fun listMirrorFiles(): Result<List<RemoteDriveFile>> {
        return runWithToken { token ->
            val rootId = ensureMirrorRootFolder().getOrThrow()
            val allItems = listFilesRecursive(token, rootId)
            allItems.filter { item ->
                SyncPolicy.shouldIncludeLocalItem(item.canonicalPath, item.name)
            }
        }
    }

    suspend fun uploadLocalFile(
        canonicalPath: String,
        sourceFile: File,
        mimeType: String?,
    ): Result<DriveItem> {
        return runWithToken { token ->
            val auth = "Bearer $token"
            val drivePath = encodeDrivePath(syncDrivePathFromCanonical(canonicalPath))
            if (sourceFile.length() <= GraphResumableTransfer.SIMPLE_UPLOAD_MAX_BYTES) {
                val mediaType = mimeType?.toMediaTypeOrNull()
                val body = sourceFile.asRequestBody(mediaType)
                graphApi.uploadSmallFile(auth, drivePath, body)
            } else {
                resumableTransfer.uploadFile(auth, drivePath, canonicalPath, sourceFile)
            }
        }
    }

    suspend fun uploadLocalStream(
        canonicalPath: String,
        inputStream: InputStream,
        length: Long,
        mimeType: String?,
    ): Result<DriveItem> {
        return runWithToken { token ->
            val temp = File.createTempFile("ogs-upload", ".bin")
            try {
                temp.outputStream().use { out -> inputStream.copyTo(out) }
                uploadLocalFile(canonicalPath, temp, mimeType).getOrThrow()
            } finally {
                temp.delete()
            }
        }
    }

    suspend fun downloadToFile(itemId: String, destination: File): Result<Unit> {
        return runWithToken { token ->
            val part = File(destination.absolutePath + ".part")
            val meta = File(destination.absolutePath + ".part.meta")
            resumableTransfer.downloadToFile("Bearer $token", itemId, destination, part, meta)
        }
    }

    suspend fun downloadThumbnailToFile(itemId: String, destination: File): Result<Unit> {
        return runWithToken { token ->
            destination.parentFile?.mkdirs()
            graphApi.downloadItemThumbnail("Bearer $token", itemId).use { body ->
                destination.outputStream().use { out ->
                    body.byteStream().copyTo(out)
                }
            }
        }
    }

    suspend fun deleteRemoteItem(itemId: String): Result<Unit> {
        return runWithToken { token ->
            graphApi.deleteItem("Bearer $token", itemId)
        }
    }

    private fun syncDrivePathFromCanonical(canonicalPath: String): String {
        val base = graphFolderPreferences.syncRootDrivePath?.trim('/')
            ?: SyncPolicy.ONEDRIVE_MIRROR_ROOT
        return SyncPaths.drivePathFromCanonical(canonicalPath, base)
    }

    private suspend fun listFilesRecursive(
        token: String,
        rootFolderId: String,
    ): List<RemoteDriveFile> {
        val files = mutableListOf<RemoteDriveFile>()
        val folders = ArrayDeque<Pair<String, String>>()
        folders.add(rootFolderId to "")

        while (folders.isNotEmpty()) {
            val (folderId, prefix) = folders.removeFirst()
            var nextLink: String? = null
            do {
                val page = if (nextLink == null) {
                    graphApi.listFolderChildren("Bearer $token", folderId)
                } else {
                    graphApi.listDriveNext(nextLink, "Bearer $token")
                }
                page.value.forEach { item ->
                    val childPrefix = if (prefix.isEmpty()) item.name else "$prefix/${item.name}"
                    if (item.folder != null) {
                        folders.add(item.id to childPrefix)
                    } else if (item.file != null) {
                        files.add(
                            RemoteDriveFile(
                                itemId = item.id,
                                name = item.name,
                                canonicalPath = childPrefix,
                                modifiedAtEpoch = parseGraphTime(item.lastModifiedDateTime),
                                sizeBytes = item.size,
                            ),
                        )
                    }
                }
                nextLink = page.nextLink
            } while (nextLink != null)
        }
        return files
    }

    private suspend fun <T> runWithToken(block: suspend (String) -> T): Result<T> {
        return runWithTokenInternal(block, allowRetryAfter401 = true)
    }

    private suspend fun <T> runWithTokenInternal(
        block: suspend (String) -> T,
        allowRetryAfter401: Boolean,
    ): Result<T> {
        val token = accessToken().getOrElse { return Result.failure(it) }
        val first = runCatching { block(token) }
        if (first.isSuccess || !allowRetryAfter401) return first
        val http = first.exceptionOrNull() as? HttpException ?: return first
        if (http.code() != 401) return first
        authManager.clearCachedAccessToken()
        val refreshed = accessToken().getOrElse { return Result.failure(it) }
        return runCatching { block(refreshed) }.recoverCatching { retryError ->
            if (retryError is HttpException && retryError.code() == 401) {
                throw IllegalStateException(
                    "Sessão Microsoft expirou (401). Em Ajustes, saia da conta e entre de novo.",
                    retryError,
                )
            }
            throw retryError
        }
    }

    private fun parseGraphTime(value: String?): Long? {
        if (value.isNullOrBlank()) return null
        return runCatching { Instant.parse(value).epochSecond }.getOrNull()
    }

    private fun encodeDrivePath(path: String): String {
        return path.trim('/').split('/')
            .joinToString("/") { segment ->
                URLEncoder.encode(segment, StandardCharsets.UTF_8.name()).replace("+", "%20")
            }
    }
}
