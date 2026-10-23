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
    /** Itens do delta ignorados por falta de caminho (antes do complemento recursivo). */
    val unresolvedDeltaSkips: Int = 0,
    /** Quantos arquivos vieram só da varredura recursiva (não estavam no delta desta passagem). */
    val supplementalFromRecursive: Int = 0,
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
            .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .writeTimeout(90, java.util.concurrent.TimeUnit.SECONDS)
            .callTimeout(120, java.util.concurrent.TimeUnit.SECONDS)
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

    /** Após limpar o índice local — força nova listagem completa na nuvem. */
    fun resetRemoteListingState() {
        graphFolderPreferences.clearDriveDeltaLink()
        graphFolderPreferences.lastFullRemoteListingAtMs = 0L
    }

    /** Verdadeiro se a seleção é diferente da pasta salva (inclui primeira configuração). */
    fun isDifferentSyncRoot(itemId: String, driveRelativePath: String): Boolean {
        return !graphFolderPreferences.matchesRoot(itemId, driveRelativePath.trim('/'))
    }

    suspend fun getDriveRootId(): Result<String> {
        return runWithToken { token ->
            graphApi.getDriveRoot("Bearer $token").id
                ?: error("Drive root sem id")
        }
    }

    suspend fun listBrowseChildren(folderId: String): Result<List<OneDriveBrowseEntry>> {
        return runWithToken { token ->
            val page = graphApi.listFolderChildren("Bearer $token", folderId)
            page.value.mapNotNull { item ->
                val id = item.id ?: return@mapNotNull null
                val name = item.name ?: return@mapNotNull null
                OneDriveBrowseEntry(
                    id = id,
                    name = name,
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
                id = created.id ?: error("Pasta criada sem id"),
                name = created.name ?: name,
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
                    ?: error("Pasta na nuvem sem id")
            } catch (http: HttpException) {
                if (http.code() != 404) throw http
                graphApi.createFolderUnderDriveRoot(
                    authorization = "Bearer $token",
                    body = CreateFolderRequest(name = SyncPolicy.ONEDRIVE_MIRROR_ROOT),
                ).id ?: error("Pasta criada sem id")
            }.also { id ->
                graphFolderPreferences.setSyncRoot(
                    itemId = id,
                    driveRelativePath = SyncPolicy.ONEDRIVE_MIRROR_ROOT,
                    label = SyncPolicy.ONEDRIVE_MIRROR_ROOT,
                )
            }
        }
    }

    /**
     * Varredura recursiva completa quando o índice Room está claramente menor que a última
     * contagem na pasta de sync (delta sozinho não descobre arquivos nunca indexados).
     */
    fun shouldForceRecursiveRemoteListing(indexRowCount: Int, userInitiated: Boolean): Boolean {
        val lastRecursive = graphFolderPreferences.lastRecursiveRemoteFileCount
        if (userInitiated) {
            if (lastRecursive <= 0) return true
            if (indexRowCount < (lastRecursive * 97 / 100).coerceAtLeast(1)) return true
        } else if (lastRecursive > 0 &&
            indexRowCount < (lastRecursive * 90 / 100).coerceAtLeast(1)
        ) {
            return true
        }
        return false
    }

    suspend fun listMirrorFiles(
        indexBaseline: List<RemoteDriveFile> = emptyList(),
        forceRecursiveComplement: Boolean = false,
    ): Result<RemoteDriveListing> {
        return runWithToken { token ->
            listMirrorFilesWithDelta(token, indexBaseline, forceRecursiveComplement)
        }
    }

    private suspend fun listMirrorFilesWithDelta(
        token: String,
        indexBaseline: List<RemoteDriveFile>,
        forceRecursiveComplement: Boolean,
    ): RemoteDriveListing {
        val rootId = configuredSyncRootItemId()
        val savedDelta = graphFolderPreferences.driveDeltaLink
        val knownByItemId = indexBaseline.associate { it.itemId to it.canonicalPath }.toMutableMap()
        return try {
            if (savedDelta != null) {
                val delta = collectDriveDeltaPages(
                    token = token,
                    start = savedDelta,
                    knownByItemId = knownByItemId,
                )
                graphFolderPreferences.driveDeltaLink = delta.deltaLink
                var files = mergeRemoteBaseline(indexBaseline, delta)
                var supplemental = 0
                if (shouldRunRecursiveRemoteComplement(
                        unresolvedDeltaSkips = delta.unresolvedDeltaSkips,
                        indexBaselineSize = indexBaseline.size,
                        mergedFileCount = files.size,
                        forceRecursiveComplement = forceRecursiveComplement,
                    )
                ) {
                    val recursive = listFilesRecursive(token, rootId)
                    val merged = mergeRemoteFileListings(files, recursive)
                    supplemental = (merged.size - files.size).coerceAtLeast(0)
                    files = merged
                    graphFolderPreferences.lastFullRemoteListingAtMs = System.currentTimeMillis()
                    graphFolderPreferences.lastRecursiveRemoteFileCount = files.size
                }
                RemoteDriveListing(
                    files = files,
                    incremental = true,
                    deltaChangeCount = deltaChangeCount(delta),
                    unresolvedDeltaSkips = delta.unresolvedDeltaSkips,
                    supplementalFromRecursive = supplemental,
                )
            } else {
                buildInitialRemoteListing(token, rootId, knownByItemId)
            }
        } catch (http: HttpException) {
            if (savedDelta != null && (http.code() == 410 || http.code() == 404)) {
                graphFolderPreferences.clearDriveDeltaLink()
                buildInitialRemoteListing(token, rootId, knownByItemId)
            } else {
                throw http
            }
        }
    }

    private suspend fun buildInitialRemoteListing(
        token: String,
        rootId: String,
        knownByItemId: MutableMap<String, String>,
    ): RemoteDriveListing {
        val delta = collectDriveDeltaPages(
            token = token,
            start = rootId,
            initialFromItemId = true,
            knownByItemId = knownByItemId,
        )
        graphFolderPreferences.driveDeltaLink = delta.deltaLink
        val fromDelta = mergeRemoteBaseline(emptyList(), delta)
        val recursive = listFilesRecursive(token, rootId)
        val files = mergeRemoteFileListings(fromDelta, recursive)
        graphFolderPreferences.lastFullRemoteListingAtMs = System.currentTimeMillis()
        graphFolderPreferences.lastRecursiveRemoteFileCount = files.size
        return RemoteDriveListing(
            files = files,
            incremental = false,
            deltaChangeCount = deltaChangeCount(delta).coerceAtLeast(files.size),
            unresolvedDeltaSkips = delta.unresolvedDeltaSkips,
            supplementalFromRecursive = (files.size - fromDelta.size).coerceAtLeast(0),
        )
    }

    private fun shouldRunRecursiveRemoteComplement(
        unresolvedDeltaSkips: Int,
        indexBaselineSize: Int,
        mergedFileCount: Int,
        forceRecursiveComplement: Boolean,
    ): Boolean {
        if (forceRecursiveComplement) return true
        // Índice vazio + delta sem alterações (ex.: após migração Room) → nuvem parecia “vazia”.
        if (indexBaselineSize == 0 && mergedFileCount == 0) return true
        if (unresolvedDeltaSkips > 0) return true
        val lastRecursive = graphFolderPreferences.lastRecursiveRemoteFileCount
        if (lastRecursive > 0 && mergedFileCount + 64 < lastRecursive) return true
        if (indexBaselineSize > 0 && mergedFileCount <= indexBaselineSize && lastRecursive > indexBaselineSize) {
            return true
        }
        val last = graphFolderPreferences.lastFullRemoteListingAtMs
        if (last <= 0L) return true
        return System.currentTimeMillis() - last >= FULL_REMOTE_LISTING_INTERVAL_MS
    }

    /** União por itemId; metadados do delta têm prioridade sobre a varredura recursiva. */
    private fun mergeRemoteFileListings(
        primary: List<RemoteDriveFile>,
        supplemental: List<RemoteDriveFile>,
    ): List<RemoteDriveFile> {
        if (supplemental.isEmpty()) return primary
        val byId = primary.associateBy { it.itemId }.toMutableMap()
        supplemental.forEach { file ->
            val existing = byId[file.itemId]
            if (existing == null) {
                byId[file.itemId] = file
            } else if (existing.canonicalPath != file.canonicalPath &&
                existing.quickXorHash == null &&
                file.quickXorHash != null
            ) {
                byId[file.itemId] = existing.copy(
                    canonicalPath = file.canonicalPath,
                    quickXorHash = file.quickXorHash,
                    sizeBytes = file.sizeBytes ?: existing.sizeBytes,
                    modifiedAtEpoch = file.modifiedAtEpoch ?: existing.modifiedAtEpoch,
                )
            }
        }
        return byId.values.toList()
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
        var unresolvedDeltaSkips: Int = 0,
    )

    private suspend fun collectDriveDeltaPages(
        token: String,
        start: String,
        initialFromItemId: Boolean = false,
        knownByItemId: MutableMap<String, String> = mutableMapOf(),
    ): DriveDeltaAccum {
        val auth = "Bearer $token"
        val acc = DriveDeltaAccum(mutableListOf(), mutableSetOf(), null)
        var nextLink: String? = null
        var started = false
        while (true) {
            coroutineContext.ensureActive()
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
            for (item in page.value) {
                if (item.deleted != null || item.removed != null) {
                    item.id?.let { acc.removedItemIds.add(it) }
                    continue
                }
                if (item.file == null) continue
                val fileName = item.name ?: continue
                val canonical = resolveCanonicalPathForDeltaFile(auth, item, knownByItemId)
                if (canonical == null) {
                    acc.unresolvedDeltaSkips++
                    continue
                }
                if (!SyncPolicy.shouldIncludeLocalItem(canonical, fileName)) continue
                val remote = remoteDriveFileFromItem(item, canonical)
                acc.changedFiles.add(remote)
                knownByItemId[remote.itemId] = canonical
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

    /** Delta muitas vezes omite parentReference.path — usa índice local ou GET do item. */
    private suspend fun resolveCanonicalPathForDeltaFile(
        auth: String,
        item: DriveItem,
        knownByItemId: Map<String, String>,
    ): String? {
        canonicalPathForDriveItem(item)?.let { return it }
        val itemId = item.id ?: return null
        knownByItemId[itemId]?.let { return it }
        resolveCanonicalPathViaItemFetch(auth, itemId)?.let { return it }
        val full = runCatching { graphApi.getDriveItem(auth, itemId) }.getOrNull() ?: return null
        canonicalPathForDriveItem(full)?.let { return it }
        resolveCanonicalPathViaItemFetch(auth, itemId)?.let { return it }
        return knownByItemId[itemId]
    }

    /** Sobe pela cadeia parentReference.id quando o delta não traz parentReference.path. */
    private suspend fun resolveCanonicalPathViaItemFetch(auth: String, startItemId: String): String? {
        val rootId = configuredSyncRootItemId()
        val segments = mutableListOf<String>()
        var currentId: String? = startItemId
        repeat(PARENT_WALK_MAX_DEPTH) {
            val id = currentId ?: return null
            if (id == rootId) {
                val raw = segments.joinToString("/")
                return if (raw.isBlank()) null else SyncPaths.shortenWhatsAppPath(raw)
            }
            val item = runCatching { graphApi.getDriveItem(auth, id) }.getOrNull() ?: return null
            canonicalPathForDriveItem(item)?.let { return it }
            val name = item.name ?: return null
            segments.add(0, name)
            currentId = item.parentReference?.id
        }
        return null
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
        val fileName = item.name ?: return null
        val raw = if (parentRel.isBlank()) fileName else "$parentRel/$fileName"
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

    /** Quando Graph não gera thumbnail, baixa prefixo da imagem e gera miniatura localmente. */
    suspend fun downloadImagePrefixForThumbnail(
        itemId: String,
        destination: File,
        maxBytes: Long = 2L * 1024L * 1024L,
        onProgress: ((bytesCompleted: Long, totalBytes: Long?) -> Unit)? = null,
    ): Result<Unit> {
        return runWithToken { token ->
            destination.parentFile?.mkdirs()
            val auth = "Bearer $token"
            val end = (maxBytes - 1L).coerceAtLeast(0L)
            resumableTransfer.downloadByteRange(
                authorization = auth,
                itemId = itemId,
                rangeHeader = "bytes=0-$end",
                destination = destination,
                totalBytesHint = maxBytes,
                onProgress = onProgress,
            )
            if (!destination.isFile || destination.length() <= 0L) {
                error("Prefixo vazio para miniatura.")
            }
        }
    }

    /** MP4/MOV: metadados (moov) costumam ficar no fim — tentar cauda para extrair frame. */
    suspend fun downloadFileSuffixForThumbnail(
        itemId: String,
        destination: File,
        maxBytes: Long = 6L * 1024L * 1024L,
        onProgress: ((bytesCompleted: Long, totalBytes: Long?) -> Unit)? = null,
    ): Result<Unit> {
        return runWithToken { token ->
            destination.parentFile?.mkdirs()
            val auth = "Bearer $token"
            resumableTransfer.downloadByteRange(
                authorization = auth,
                itemId = itemId,
                rangeHeader = "bytes=-$maxBytes",
                destination = destination,
                totalBytesHint = maxBytes,
                onProgress = onProgress,
            )
            if (!destination.isFile || destination.length() <= 0L) {
                error("Sufixo vazio para miniatura de vídeo.")
            }
        }
    }

    suspend fun downloadThumbnailToFile(
        itemId: String,
        destination: File,
        onProgress: ((bytesCompleted: Long, totalBytes: Long?) -> Unit)? = null,
    ): Result<Unit> {
        return runWithToken { token ->
            destination.parentFile?.mkdirs()
            val auth = "Bearer $token"
            val large = runCatching {
                graphApi.downloadItemThumbnailLarge(auth, itemId).use { body ->
                    destination.outputStream().use { out ->
                        copyStreamWithProgress(body.byteStream(), out, body.contentLength(), onProgress)
                    }
                }
            }
            if (large.isSuccess && destination.length() > 0L) return@runWithToken
            destination.delete()
            graphApi.downloadItemThumbnailMedium(auth, itemId).use { body ->
                destination.outputStream().use { out ->
                    copyStreamWithProgress(body.byteStream(), out, body.contentLength(), onProgress)
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

        suspend fun copyStreamWithProgress(
            input: InputStream,
            output: java.io.OutputStream,
            totalHint: Long,
            onProgress: ((bytesCompleted: Long, totalBytes: Long?) -> Unit)?,
        ) {
            val total = totalHint.takeIf { it > 0L }
            var copied = 0L
            onProgress?.invoke(0L, total)
            val buffer = ByteArray(STREAM_BUFFER_BYTES)
            while (true) {
                coroutineContext.ensureActive()
                val read = input.read(buffer)
                if (read <= 0) break
                output.write(buffer, 0, read)
                copied += read
                onProgress?.invoke(copied, total ?: copied.takeIf { it > 0L })
            }
            onProgress?.invoke(copied, total ?: copied.takeIf { it > 0L })
        }
        const val FULL_REMOTE_LISTING_INTERVAL_MS = 24L * 60L * 60L * 1000L
        const val PARENT_WALK_MAX_DEPTH = 48

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

    /** Metadados atuais na nuvem (hash + tamanho) para decidir se o recall precisa baixar bytes. */
    suspend fun getRemoteDriveFileByItemId(
        itemId: String,
        canonicalPathFallback: String,
    ): Result<RemoteDriveFile?> {
        return runWithToken { token ->
            val item = graphApi.getDriveItem("Bearer $token", itemId)
            if (item.file == null) return@runWithToken null
            val canonical = canonicalPathForDriveItem(item) ?: canonicalPathFallback
            remoteDriveFileFromItem(item, canonical)
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
        val itemId = item.id ?: error("DriveItem sem id")
        val name = item.name ?: canonicalPath.substringAfterLast('/')
        return RemoteDriveFile(
            itemId = itemId,
            name = name,
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
            coroutineContext.ensureActive()
            val (folderId, prefix) = folders.removeFirst()
            var nextLink: String? = null
            do {
                coroutineContext.ensureActive()
                val page = if (nextLink == null) {
                    graphApi.listFolderChildren("Bearer $token", folderId)
                } else {
                    graphApi.listDriveNext(nextLink, "Bearer $token")
                }
                page.value.forEach { item ->
                    val itemId = item.id ?: return@forEach
                    val itemName = item.name ?: return@forEach
                    val childPrefix = if (prefix.isEmpty()) itemName else "$prefix/$itemName"
                    if (item.folder != null) {
                        folders.add(itemId to childPrefix)
                    } else if (item.file != null) {
                        val canonical = SyncPaths.shortenWhatsAppPath(childPrefix)
                        if (!SyncPolicy.shouldIncludeLocalItem(canonical, itemName)) return@forEach
                        files.add(
                            remoteDriveFileFromItem(
                                item,
                                canonical,
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
