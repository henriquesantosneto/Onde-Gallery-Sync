package com.onegallery.sync.data.onedrive

import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * Upload/download resumável via Microsoft Graph (sessão de upload + HTTP Range).
 * Fragmentos múltiplos de 320 KiB, conforme documentação Graph.
 */
class GraphResumableTransfer(
    private val graphApi: GraphApiService,
    private val uploadSessionStore: UploadSessionStore,
    private val httpClient: OkHttpClient = defaultClient(),
) {
    private val statusAdapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(UploadSessionStatus::class.java)
    private val itemAdapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(DriveItem::class.java)

    suspend fun uploadFile(
        authorization: String,
        drivePath: String,
        canonicalPath: String,
        sourceFile: File,
    ): DriveItem {
        val fileSize = sourceFile.length()
        val fileModified = sourceFile.lastModified()
        require(fileSize > SIMPLE_UPLOAD_MAX_BYTES) {
            "Use upload simples para arquivos ≤ 4 MiB"
        }

        var session = loadValidSession(canonicalPath, drivePath, sourceFile)
            ?: createAndPersistSession(authorization, drivePath, canonicalPath, fileSize, fileModified)

        var uploadUrl = session.uploadUrl
        if (isExpired(session.expirationDateTime)) {
            uploadSessionStore.clear(canonicalPath)
            session = createAndPersistSession(authorization, drivePath, canonicalPath, fileSize, fileModified)
            uploadUrl = session.uploadUrl
        }

        var nextStart = queryNextByteOffset(uploadUrl).coerceAtMost(fileSize)
        if (nextStart >= fileSize) nextStart = 0L

        RandomAccessFile(sourceFile, "r").use { input ->
            while (nextStart < fileSize) {
                val chunkEnd = minOf(nextStart + UPLOAD_FRAGMENT_BYTES - 1, fileSize - 1)
                val chunkLength = (chunkEnd - nextStart + 1).toInt()
                val buffer = ByteArray(chunkLength)
                input.seek(nextStart)
                input.readFully(buffer)

                val httpResponse = httpClient.newCall(
                    Request.Builder()
                        .url(uploadUrl)
                        .put(buffer.toRequestBody(OCTET_STREAM))
                        .header("Content-Length", chunkLength.toString())
                        .header("Content-Range", "bytes $nextStart-$chunkEnd/$fileSize")
                        .build(),
                ).execute()

                httpResponse.use { response ->
                    val body = response.body?.string().orEmpty()
                    when (response.code) {
                        201, 200 -> {
                            uploadSessionStore.clear(canonicalPath)
                            if (body.isNotBlank()) {
                                itemAdapter.fromJson(body)?.let { return it }
                            }
                            throw IllegalStateException(
                                "Upload concluído sem metadados do item — sincronize de novo.",
                            )
                        }
                        202 -> {
                            nextStart = parseNextStart(body, chunkEnd + 1)
                            uploadSessionStore.save(session)
                        }
                        404, 410 -> {
                            uploadSessionStore.clear(canonicalPath)
                            session = createAndPersistSession(
                                authorization,
                                drivePath,
                                canonicalPath,
                                fileSize,
                                fileModified,
                            )
                            uploadUrl = session.uploadUrl
                            nextStart = 0L
                        }
                        else -> throw IOException("Upload falhou: HTTP ${response.code} ${response.message}")
                    }
                }
            }
        }
        uploadSessionStore.clear(canonicalPath)
        throw IllegalStateException("Upload interrompido antes de concluir.")
    }

    suspend fun downloadToFile(
        authorization: String,
        itemId: String,
        destination: File,
        partFile: File,
        metaFile: File,
    ) {
        destination.parentFile?.mkdirs()
        var totalBytes = readDownloadMeta(metaFile, itemId)
        if (totalBytes == null) {
            totalBytes = graphApi.getDriveItem(authorization, itemId).size
            if (totalBytes != null) writeDownloadMeta(metaFile, itemId, totalBytes)
        }

        var downloaded = if (partFile.exists()) partFile.length() else 0L
        val knownTotal = totalBytes
        if (knownTotal != null && downloaded > knownTotal) {
            partFile.delete()
            downloaded = 0L
        }
        if (knownTotal != null && downloaded == knownTotal) {
            finalizeDownload(partFile, destination, metaFile)
            return
        }

        val url = "https://graph.microsoft.com/v1.0/me/drive/items/$itemId/content"
        while (true) {
            val requestBuilder = Request.Builder()
                .url(url)
                .header("Authorization", authorization)
            if (downloaded > 0L) {
                requestBuilder.header("Range", "bytes=$downloaded-")
            }
            val httpResponse = httpClient.newCall(requestBuilder.get().build()).execute()
            httpResponse.use { response ->
                if (!response.isSuccessful && response.code != 206) {
                    throw IOException("Download falhou: HTTP ${response.code} ${response.message}")
                }
                val body = response.body ?: throw IllegalStateException("Corpo vazio no download")
                FileOutputStream(partFile, downloaded > 0L).use { out ->
                    body.byteStream().copyTo(out)
                }
                downloaded = partFile.length()
                val totalFromHeader = parseTotalFromContentRange(response.header("Content-Range"))
                    ?: totalBytes
                if (totalFromHeader != null) {
                    writeDownloadMeta(metaFile, itemId, totalFromHeader)
                    totalBytes = totalFromHeader
                }
                val completeTotal = totalBytes
                if (completeTotal != null && downloaded >= completeTotal) {
                    finalizeDownload(partFile, destination, metaFile)
                    return
                }
                if (response.code == 200 && completeTotal == null) {
                    finalizeDownload(partFile, destination, metaFile)
                    return
                }
            }
        }
    }

    private suspend fun createAndPersistSession(
        authorization: String,
        drivePath: String,
        canonicalPath: String,
        fileSize: Long,
        fileModified: Long,
    ): PersistedUploadSession {
        val created = graphApi.createUploadSession(
            authorization = authorization,
            itemPath = drivePath,
        )
        return PersistedUploadSession(
            canonicalPath = canonicalPath,
            drivePath = drivePath,
            uploadUrl = created.uploadUrl,
            expirationDateTime = created.expirationDateTime,
            fileSize = fileSize,
            fileLastModified = fileModified,
        ).also { uploadSessionStore.save(it) }
    }

    private fun loadValidSession(
        canonicalPath: String,
        drivePath: String,
        sourceFile: File,
    ): PersistedUploadSession? {
        val saved = uploadSessionStore.load(canonicalPath) ?: return null
        if (saved.drivePath != drivePath) return null
        if (saved.fileSize != sourceFile.length()) return null
        if (saved.fileLastModified != sourceFile.lastModified()) return null
        if (isExpired(saved.expirationDateTime)) return null
        return saved
    }

    private fun parseNextStart(responseBody: String, fallback: Long): Long {
        if (responseBody.isBlank()) return fallback
        val status = statusAdapter.fromJson(responseBody)
        val range = status?.nextExpectedRanges?.firstOrNull() ?: return fallback
        return parseRangeStart(range).coerceAtLeast(fallback)
    }

    private fun queryNextByteOffset(uploadUrl: String): Long {
        val response = httpClient.newCall(Request.Builder().url(uploadUrl).get().build()).execute()
        response.use { httpResponse ->
            if (!httpResponse.isSuccessful) return 0L
            return parseNextStart(httpResponse.body?.string().orEmpty(), 0L)
        }
    }

    private fun parseRangeStart(range: String): Long {
        val trimmed = range.removePrefix("bytes=").trim()
        val startPart = trimmed.substringBefore("-").substringBefore("/")
        return startPart.toLongOrNull() ?: 0L
    }

    private fun parseTotalFromContentRange(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val totalPart = header.substringAfter("/").trim()
        return totalPart.toLongOrNull()
    }

    private fun isExpired(expirationDateTime: String?): Boolean {
        if (expirationDateTime.isNullOrBlank()) return false
        return runCatching {
            Instant.parse(expirationDateTime).isBefore(Instant.now())
        }.getOrDefault(false)
    }

    private fun finalizeDownload(partFile: File, destination: File, metaFile: File) {
        if (destination.exists()) destination.delete()
        if (!partFile.renameTo(destination)) {
            partFile.copyTo(destination, overwrite = true)
            partFile.delete()
        }
        metaFile.delete()
    }

    private fun readDownloadMeta(metaFile: File, itemId: String): Long? {
        if (!metaFile.isFile) return null
        val lines = metaFile.readLines()
        if (lines.size < 2 || lines[0] != itemId) return null
        return lines[1].toLongOrNull()
    }

    private fun writeDownloadMeta(metaFile: File, itemId: String, totalBytes: Long) {
        metaFile.writeText("$itemId\n$totalBytes\n")
    }

    companion object {
        /** Limite Graph para PUT simples em /content. */
        const val SIMPLE_UPLOAD_MAX_BYTES = 4L * 1024L * 1024L

        /** 10 × 320 KiB — múltiplo exigido pela API de sessão. */
        private const val UPLOAD_FRAGMENT_BYTES = 320L * 1024L * 10L

        private val OCTET_STREAM = "application/octet-stream".toMediaType()

        private fun defaultClient(): OkHttpClient {
            return OkHttpClient.Builder()
                .connectTimeout(60, TimeUnit.SECONDS)
                .readTimeout(5, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .build()
        }
    }
}
