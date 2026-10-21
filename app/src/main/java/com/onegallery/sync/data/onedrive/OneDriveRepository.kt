package com.onegallery.sync.data.onedrive

import android.content.Context
import com.onegallery.sync.auth.MsalAuthManager
import com.onegallery.sync.data.local.MediaLocation
import com.onegallery.sync.data.local.MediaCaptureTimeResolver
import com.onegallery.sync.data.local.MediaLocationResolver
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
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
    val quickXorHash: String? = null,
)

data class RemoteDriveListing(
    val files: List<RemoteDriveFile>,
    /** true = só alterações desde o último sync (Graph delta). */
    val incremental: Boolean,
    val deltaChangeCount: Int = 0,
)

data class OneDriveBrowseEntry(
    val id: String,
    val name: String,
    val isFolder: Boolean,
    val childCount: Int?,
)

class OneDriveRepository(
    private val appContext: Context,
    private val authManager: MsalAuthManager,
    private val graphFolderPreferences: GraphFolderPreferences,
) {
    private val uploadSessionStore = UploadSessionStore(appContext)
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

    suspend fun ensureGraphSession(): Result<Unit> = authManager.ensureGraphSession()

    fun syncRootLabel(): String? = graphFolderPreferences.displayLabel

    fun isSyncRootConfigured(): Boolean = graphFolderPreferences.isConfigured()

    fun setSyncRoot(itemId: String, driveRelativePath: String, label: String) {
        graphFolderPreferences.setSyncRoot(itemId, driveRelativePath, label)
    }

    /** Verdadeiro se a seleção é diferente da pasta salva (inclui primeira configuração). */
    fun isDifferentSyncRoot(itemId: String, driveRelativePath: String): Boolean {
        return !graphFolderPreferences.matchesRoot(itemId, driveRelativePath.trim('/'))
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

    suspend fun listMirrorFiles(indexBaseline: List<RemoteDriveFile> = emptyList()): Result<RemoteDriveListing> {
        return runWithToken { token ->
            listMirrorFilesWithDelta(token, indexBaseline)
        }
    }

    private suspend fun listMirrorFilesWithDelta(
        token: String,
        indexBaseline: List<RemoteDriveFile>,
    ): RemoteDriveListing {
        val rootId = configuredSyncRootItemId()
        val savedDelta = graphFolderPreferences.driveDeltaLink
        return try {
            if (savedDelta != null) {
                val delta = collectDriveDeltaPages(token, savedDelta)
                graphFolderPreferences.driveDeltaLink = delta.deltaLink
                RemoteDriveListing(
                    files = mergeRemoteBaseline(indexBaseline, delta),
                    incremental = true,
                    deltaChangeCount = deltaChangeCount(delta),
                )
            } else {
                val delta = collectDriveDeltaPages(token, rootId, initialFromItemId = true)
                graphFolderPreferences.driveDeltaLink = delta.deltaLink
                RemoteDriveListing(
                    files = mergeRemoteBaseline(emptyList(), delta),
                    incremental = false,
                    deltaChangeCount = deltaChangeCount(delta),
                )
            }
        } catch (http: HttpException) {
            if (savedDelta != null && (http.code() == 410 || http.code() == 404)) {
                graphFolderPreferences.clearDriveDeltaLink()
                val delta = collectDriveDeltaPages(token, rootId, initialFromItemId = true)
                graphFolderPreferences.driveDeltaLink = delta.deltaLink
                RemoteDriveListing(
                    files = mergeRemoteBaseline(emptyList(), delta),
                    incremental = false,
                    deltaChangeCount = deltaChangeCount(delta),
                )
            } else {
                throw http
            }
        }
    }

    private fun configuredSyncRootItemId(): String {
        if (graphFolderPreferences.isConfigured()) {
            return graphFolderPreferences.syncRootItemId!!
        }
        throw IllegalStateException("Pasta na nuvem não configurada")
    }

    private data class DriveDeltaAccum(
        val changedFiles: MutableList<RemoteDriveFile>,
        val removedItemIds: MutableSet<String>,
        var deltaLink: String?,
    )

    private suspend fun collectDriveDeltaPages(
        token: String,
        start: String,
        initialFromItemId: Boolean = false,
    ): DriveDeltaAccum {
        val auth = "Bearer $token"
        val acc = DriveDeltaAccum(mutableListOf(), mutableSetOf(), null)
        var nextLink: String? = null
        var started = false
        while (true) {
            val page = when {
                !started && initialFromItemId ->
                    graphApi.listFolderDeltaInitial(auth, start)
                !started ->
                    graphApi.listDriveNext(start, auth)
                nextLink != null ->
                    graphApi.listDriveNext(nextLink!!, auth)
                else -> break
            }
            started = true
            page.value.forEach { item ->
                if (item.deleted != null) {
                    acc.removedItemIds.add(item.id)
                    return@forEach
                }
                if (item.file == null) return@forEach
                val canonical = canonicalPathForDriveItem(item) ?: return@forEach
                if (!SyncPolicy.shouldIncludeLocalItem(canonical, item.name)) return@forEach
                acc.changedFiles.add(remoteDriveFileFromItem(item, canonical))
            }
            nextLink = page.nextLink
            if (page.deltaLink != null) {
                acc.deltaLink = page.deltaLink
            }
            if (nextLink == null) break
        }
        if (acc.deltaLink.isNullOrBlank()) {
            error("Graph delta terminou sem @odata.deltaLink")
        }
        return acc
    }

    private fun deltaChangeCount(delta: DriveDeltaAccum): Int {
        return delta.changedFiles.size + delta.removedItemIds.size
    }

    private fun mergeRemoteBaseline(
        baseline: List<RemoteDriveFile>,
        delta: DriveDeltaAccum,
    ): List<RemoteDriveFile> {
        val byPath = baseline.associateBy { it.canonicalPath }.toMutableMap()
        if (delta.removedItemIds.isNotEmpty()) {
            byPath.entries.removeIf { it.value.itemId in delta.removedItemIds }
        }
        delta.changedFiles.forEach { file ->
            byPath.entries.removeIf { entry ->
                entry.value.itemId == file.itemId && entry.key != file.canonicalPath
            }
            byPath[file.canonicalPath] = file
        }
        return byPath.values.toList()
    }

    private fun canonicalPathForDriveItem(item: DriveItem): String? {
        val parentPath = item.parentReference?.path ?: return null
        val syncRoot = graphFolderPreferences.syncRootDrivePath?.trim('/')
            ?: SyncPolicy.ONEDRIVE_MIRROR_ROOT
        val decoded = java.net.URLDecoder.decode(parentPath, StandardCharsets.UTF_8.name())
        val rootMarker = "root:"
        val idx = decoded.indexOf(rootMarker, ignoreCase = true)
        if (idx < 0) return null
        val drivePath = decoded.substring(idx + rootMarker.length).trim('/')
        val parentRel = when {
            syncRoot.isBlank() -> drivePath
            drivePath.equals(syncRoot, ignoreCase = true) -> ""
            drivePath.startsWith("$syncRoot/", ignoreCase = true) ->
                drivePath.substring(syncRoot.length + 1)
            else -> return null
        }
        val raw = if (parentRel.isBlank()) item.name else "$parentRel/${item.name}"
        return SyncPaths.shortenWhatsAppPath(raw)
    }

    suspend fun uploadLocalFile(
        canonicalPath: String,
        sourceFile: File,
        mimeType: String?,
        onProgress: ((bytesCompleted: Long, totalBytes: Long?) -> Unit)? = null,
    ): Result<DriveItem> {
        return runWithToken { token ->
            val auth = "Bearer $token"
            val drivePath = encodeDrivePath(syncDrivePathFromCanonical(canonicalPath))
            val fileSize = sourceFile.length()
            if (sourceFile.length() <= GraphResumableTransfer.SIMPLE_UPLOAD_MAX_BYTES) {
                onProgress?.invoke(0L, fileSize)
                val mediaType = mimeType?.toMediaTypeOrNull()
                val body = sourceFile.asRequestBody(mediaType)
                val item = graphApi.uploadSmallFile(auth, drivePath, body)
                onProgress?.invoke(fileSize, fileSize)
                item
            } else {
                resumableTransfer.uploadFile(auth, drivePath, canonicalPath, sourceFile) { done, total ->
                    onProgress?.invoke(done, total)
                }
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

    suspend fun downloadToFile(
        itemId: String,
        destination: File,
        onProgress: ((bytesCompleted: Long, totalBytes: Long?) -> Unit)? = null,
    ): Result<Unit> {
        return runWithToken { token ->
            val part = File(destination.absolutePath + ".part")
            val meta = File(destination.absolutePath + ".part.meta")
            resumableTransfer.downloadToFile("Bearer $token", itemId, destination, part, meta, onProgress)
        }
    }

    /** Download direto (stream único) — mais estável para recall / visualização sob demanda. */
    suspend fun downloadItemContentToFile(itemId: String, destination: File): Result<Unit> {
        return runWithToken { token ->
            val auth = "Bearer $token"
            val temp = File(destination.absolutePath + ".recall.part")
            temp.parentFile?.mkdirs()
            try {
                graphApi.downloadItemContent(auth, itemId).use { body ->
                    temp.outputStream().buffered().use { out ->
                        body.byteStream().buffered().use { input ->
                            val buffer = ByteArray(STREAM_BUFFER_BYTES)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                out.write(buffer, 0, read)
                            }
                        }
                    }
                }
                if (!temp.isFile || temp.length() <= 0L) {
                    error("Download da nuvem veio vazio.")
                }
                if (destination.exists()) destination.delete()
                if (!temp.renameTo(destination)) {
                    temp.inputStream().buffered().use { input ->
                        destination.outputStream().buffered().use { output ->
                            val buffer = ByteArray(STREAM_BUFFER_BYTES)
                            while (true) {
                                coroutineContext.ensureActive()
                                val read = input.read(buffer)
                                if (read <= 0) break
                                output.write(buffer, 0, read)
                            }
                        }
                    }
                    temp.delete()
                }
                File(destination.absolutePath + ".part").delete()
                File(destination.absolutePath + ".part.meta").delete()
            } catch (t: Throwable) {
                temp.delete()
                throw t
            }
        }
    }

    suspend fun downloadThumbnailToFile(itemId: String, destination: File): Result<Unit> {
        return runWithToken { token ->
            destination.parentFile?.mkdirs()
            val auth = "Bearer $token"
            val large = runCatching {
                graphApi.downloadItemThumbnailLarge(auth, itemId).use { body ->
                    destination.outputStream().use { out ->
                        body.byteStream().copyTo(out)
                    }
                }
            }
            if (large.isSuccess && destination.length() > 0L) return@runWithToken
            destination.delete()
            graphApi.downloadItemThumbnailMedium(auth, itemId).use { body ->
                destination.outputStream().use { out ->
                    body.byteStream().copyTo(out)
                }
            }
        }
    }

    /**
     * Lê GPS do EXIF na nuvem (até ~512 KiB do início/fim do arquivo), sem baixar o original completo.
     */
    suspend fun probeExifLocationFromItem(
        itemId: String,
        locationResolver: MediaLocationResolver,
        fileNameHint: String? = null,
    ): Result<MediaLocation?> {
        return runWithToken { token ->
            val temp = File.createTempFile("ogs-exif-probe-", exifProbeTempSuffix(fileNameHint), appContext.cacheDir)
            try {
                resumableTransfer.downloadByteRange(
                    authorization = "Bearer $token",
                    itemId = itemId,
                    rangeHeader = "bytes=0-${EXIF_PROBE_BYTES - 1}",
                    destination = temp,
                )
                locationResolver.resolveFromFile(temp)?.let { return@runWithToken it }
                val size = graphApi.getDriveItem("Bearer $token", itemId).size
                if (size != null && size > EXIF_PROBE_BYTES) {
                    resumableTransfer.downloadByteRange(
                        authorization = "Bearer $token",
                        itemId = itemId,
                        rangeHeader = "bytes=-$EXIF_PROBE_BYTES",
                        destination = temp,
                    )
                    locationResolver.resolveFromFile(temp)
                } else {
                    null
                }
            } finally {
                temp.delete()
            }
        }
    }

    /** Lê data de captura no EXIF na nuvem (mesmo alcance de bytes que o GPS). */
    suspend fun probeExifCaptureTimeFromItem(
        itemId: String,
        captureResolver: MediaCaptureTimeResolver,
        fileNameHint: String? = null,
    ): Result<Long?> {
        return runWithToken { token ->
            val temp = File.createTempFile("ogs-exif-capture-", exifProbeTempSuffix(fileNameHint), appContext.cacheDir)
            try {
                resumableTransfer.downloadByteRange(
                    authorization = "Bearer $token",
                    itemId = itemId,
                    rangeHeader = "bytes=0-${EXIF_PROBE_BYTES - 1}",
                    destination = temp,
                )
                captureResolver.resolveFromFile(temp)?.let { return@runWithToken it }
                val size = graphApi.getDriveItem("Bearer $token", itemId).size
                if (size != null && size > EXIF_PROBE_BYTES) {
                    resumableTransfer.downloadByteRange(
                        authorization = "Bearer $token",
                        itemId = itemId,
                        rangeHeader = "bytes=-$EXIF_PROBE_BYTES",
                        destination = temp,
                    )
                    captureResolver.resolveFromFile(temp)
                } else {
                    null
                }
            } finally {
                temp.delete()
            }
        }
    }

    private companion object {
        const val EXIF_PROBE_BYTES = 512 * 1024L
        const val STREAM_BUFFER_BYTES = 64 * 1024

        fun exifProbeTempSuffix(fileName: String?): String {
            if (fileName.isNullOrBlank()) return ".jpg"
            val ext = fileName.substringAfterLast('.', "").lowercase().take(8)
            return when (ext) {
                "jpg", "jpeg", "heic", "heif", "png", "webp", "dng", "tif", "tiff", "mp4", "mov", "m4v" ->
                    ".$ext"
                else -> ".jpg"
            }
        }
    }

    suspend fun deleteRemoteItem(itemId: String): Result<Unit> {
        return runWithToken { token ->
            graphApi.deleteItem("Bearer $token", itemId)
        }
    }

    /** Confirma se já existe arquivo no caminho de sync (útil quando o delta não listou o item). */
    suspend fun lookupRemoteFileAtCanonicalPath(canonicalPath: String): Result<RemoteDriveFile?> {
        return runWithToken { token ->
            val auth = "Bearer $token"
            val drivePath = encodeDrivePath(syncDrivePathFromCanonical(canonicalPath))
            try {
                val item = graphApi.getItemByDrivePath(auth, drivePath)
                if (item.file == null) return@runWithToken null
                val canonical = canonicalPathForDriveItem(item) ?: canonicalPath
                remoteDriveFileFromItem(item, canonical)
            } catch (http: HttpException) {
                if (http.code() == 404) null else throw http
            }
        }
    }

    private fun remoteDriveFileFromItem(item: DriveItem, canonicalPath: String): RemoteDriveFile {
        return RemoteDriveFile(
            itemId = item.id,
            name = item.name,
            canonicalPath = canonicalPath,
            modifiedAtEpoch = parseGraphTime(item.lastModifiedDateTime),
            sizeBytes = item.size,
            quickXorHash = item.file?.hashes?.quickXorHash,
        )
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
                            remoteDriveFileFromItem(
                                item,
                                SyncPaths.shortenWhatsAppPath(childPrefix),
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
        return withContext(Dispatchers.IO) {
            runWithTokenInternal(block, allowRetryAfter401 = true)
        }
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
