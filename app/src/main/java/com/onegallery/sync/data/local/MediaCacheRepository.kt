package com.onegallery.sync.data.local

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.File
import java.util.concurrent.ConcurrentHashMap

class MediaCacheRepository(context: Context) {
    private val originalsDir = File(context.cacheDir, "originals").apply { mkdirs() }
    private val thumbsDir = File(context.cacheDir, "thumbs").apply { mkdirs() }
    private val thumbExistsCache = ConcurrentHashMap<String, Boolean>()
    /** Nomes de arquivo em `thumbs/` (sem decode) — aquecido na abertura do app. */
    private val thumbOnDiskBySafeName = ConcurrentHashMap<String, Boolean>()

    /** Disparado após cada miniatura gravada (`canonicalPath` do item). */
    @Volatile
    var onThumbnailCommitted: ((canonicalPath: String) -> Unit)? = null

    fun originalFile(canonicalPath: String): File {
        val safe = safeFileName(canonicalPath)
        return File(originalsDir, safe)
    }

    fun originalPartFile(canonicalPath: String): File {
        return File(originalsDir, safeFileName(canonicalPath) + ".part")
    }

    fun originalPartMetaFile(canonicalPath: String): File {
        return File(originalsDir, safeFileName(canonicalPath) + ".part.meta")
    }

    private fun safeFileName(canonicalPath: String): String {
        return canonicalPath.replace('/', '_').replace(':', '_')
    }

    fun thumbFile(canonicalPath: String): File {
        val safe = safeFileName(canonicalPath)
        return File(thumbsDir, safe)
    }

    fun thumbStagingFile(canonicalPath: String): File {
        return File(thumbsDir, safeFileName(canonicalPath) + ".new")
    }

    fun thumbFileByRemoteItemId(oneDriveItemId: String): File {
        val safe = oneDriveItemId.replace(Regex("[^a-zA-Z0-9._-]"), "_")
        return File(thumbsDir, "item_$safe")
    }

    /** Substitui a miniatura só quando a nova já foi gravada (evita “buraco” na grade). */
    fun commitThumbnail(
        stagingFile: File,
        canonicalPath: String,
        oneDriveItemId: String? = null,
    ): File? {
        if (!stagingFile.isFile || stagingFile.length() <= 0L) {
            stagingFile.delete()
            return null
        }
        val dest = thumbFile(canonicalPath)
        dest.parentFile?.mkdirs()
        if (dest.exists() && !dest.delete()) {
            stagingFile.inputStream().buffered().use { input ->
                dest.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            stagingFile.delete()
            return dest.takeIf { it.length() > 0L }
        }
        if (!stagingFile.renameTo(dest)) {
            stagingFile.inputStream().buffered().use { input ->
                dest.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            stagingFile.delete()
        }
        val committed = dest.takeIf { it.isFile && it.length() > 0L }
        if (committed != null) {
            rememberThumbOnDisk(canonicalPath, committed)
            oneDriveItemId?.takeIf { it.isNotBlank() }?.let { itemId ->
                mirrorThumbToRemoteItemAlias(committed, itemId)
            }
            onThumbnailCommitted?.invoke(canonicalPath)
        }
        return committed
    }

    /** Varre `thumbs/` uma vez (IO barato) para acelerar filtros com biblioteca grande. */
    fun warmThumbnailExistsCache() {
        val files = thumbsDir.listFiles() ?: return
        for (file in files) {
            if (!file.isFile || file.name.endsWith(".new")) continue
            if (file.length() <= 24L) continue
            thumbOnDiskBySafeName[file.name] = true
        }
    }

    /**
     * Checagem O(1)/stat para montar a galeria — **sem** BitmapFactory nem cópia de alias.
     */
    /** Grade/capas: ignora stubs mínimos (8×8) que pintam preto ao ampliar. */
    fun isThumbnailCachedFast(canonicalPath: String, oneDriveItemId: String? = null): Boolean {
        return displayableThumbnailIfCached(canonicalPath, oneDriveItemId) != null
    }

    /**
     * Montagem do índice (~milhares de linhas): só stat/tamanho, sem decode nem cópia de alias.
     * A UI valida o bitmap ao exibir ([displayableThumbnailIfCached]).
     */
    fun thumbnailFileForIndexBuild(canonicalPath: String, oneDriveItemId: String? = null): File? {
        val minBytes = MIN_DISPLAYABLE_THUMB_BYTES
        if (thumbExistsCache[canonicalPath] == true) {
            val cached = thumbFile(canonicalPath)
            if (cached.isFile && cached.length() >= minBytes) return cached
            thumbExistsCache.remove(canonicalPath)
        }
        val safeName = safeFileName(canonicalPath)
        if (thumbOnDiskBySafeName[safeName] == true) {
            val file = thumbFile(canonicalPath)
            if (file.isFile && file.length() >= minBytes) {
                rememberThumbOnDisk(canonicalPath, file)
                return file
            }
        }
        val file = thumbFile(canonicalPath)
        if (file.isFile && file.length() >= minBytes && !isSyncStubPlaceholderThumbnail(file)) {
            rememberThumbOnDisk(canonicalPath, file)
            return file
        }
        val itemId = oneDriveItemId?.takeIf { it.isNotBlank() } ?: return null
        val alias = thumbFileByRemoteItemId(itemId)
        return alias.takeIf {
            it.isFile && it.length() >= minBytes && !isSyncStubPlaceholderThumbnail(it)
        }
    }

    fun displayableThumbnailIfCached(
        canonicalPath: String,
        oneDriveItemId: String? = null,
    ): File? {
        val file = thumbnailIfCached(canonicalPath, oneDriveItemId) ?: return null
        if (isSyncStubPlaceholderThumbnail(file)) return null
        if (isSyncFailurePlaceholderThumbnail(file)) return null
        return file.takeIf { isDisplayableGalleryThumb(it) }
    }

    /** Grade: JPEG real ou placeholder de desistência (96×96) — não conta stub 32×32 legado. */
    fun galleryThumbnailResolved(
        canonicalPath: String,
        oneDriveItemId: String?,
        requireVideoAcceptable: Boolean,
    ): File? {
        val file = displayableThumbnailIfCached(canonicalPath, oneDriveItemId) ?: return null
        if (!requireVideoAcceptable) return file
        return file.takeIf { VideoThumbnailHelper.isDisplayableVideoThumbnailFile(it) }
    }

    /** Miniatura em disco incluindo cinza 96×96 (estatísticas / fila). */
    fun committedThumbnailIncludingGiveUp(
        canonicalPath: String,
        oneDriveItemId: String? = null,
    ): File? {
        val file = thumbnailIfCached(canonicalPath, oneDriveItemId) ?: return null
        if (isSyncStubPlaceholderThumbnail(file)) return null
        return file.takeIf { isDisplayableGalleryThumb(it) }
    }

    /** Placeholder cinza de sync (32×32) — não conta como miniatura real. */
    fun isSyncStubPlaceholderThumbnail(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (file.length() > SYNC_STUB_PLACEHOLDER_MAX_BYTES) return false
        val (w, h) = decodeBounds(file) ?: return false
        return w == MIN_DISPLAYABLE_THUMB_EDGE_PX && h == MIN_DISPLAYABLE_THUMB_EDGE_PX
    }

    /** Cinza 96×96 após desistência de prefetch — não substitui foto ainda no aparelho. */
    fun isSyncFailurePlaceholderThumbnail(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        if (isSyncStubPlaceholderThumbnail(file)) return false
        val (w, h) = decodeBounds(file) ?: return false
        return w == SYNC_FAILURE_PLACEHOLDER_EDGE_PX && h == SYNC_FAILURE_PLACEHOLDER_EDGE_PX
    }

    private fun rememberThumbOnDisk(canonicalPath: String, file: File) {
        thumbExistsCache[canonicalPath] = true
        thumbOnDiskBySafeName[file.name] = true
    }

    private fun mirrorThumbToRemoteItemAlias(source: File, oneDriveItemId: String) {
        val alias = thumbFileByRemoteItemId(oneDriveItemId)
        runCatching {
            source.inputStream().buffered().use { input ->
                alias.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        }
    }

    fun hasOriginal(canonicalPath: String): Boolean {
        val file = originalFile(canonicalPath)
        return file.isFile && file.length() > 0L
    }

    fun originalIfCached(canonicalPath: String): File? =
        originalFile(canonicalPath).takeIf { it.isFile && it.length() > 0L }

    fun deleteCached(canonicalPath: String) {
        deleteOriginalTransferArtifacts(canonicalPath)
        invalidateCommittedThumbnail(canonicalPath, oneDriveItemId = null)
    }

    fun invalidateCommittedThumbnail(canonicalPath: String, oneDriveItemId: String?) {
        val file = thumbFile(canonicalPath)
        thumbExistsCache.remove(canonicalPath)
        thumbOnDiskBySafeName.remove(file.name)
        if (file.isFile) file.delete()
        oneDriveItemId?.takeIf { it.isNotBlank() }?.let { itemId ->
            thumbFileByRemoteItemId(itemId).delete()
        }
    }

    fun deleteOriginalOnly(canonicalPath: String) {
        deleteOriginalTransferArtifacts(canonicalPath)
    }

    fun deleteOriginalTransferArtifacts(canonicalPath: String) {
        originalFile(canonicalPath).delete()
        originalPartFile(canonicalPath).delete()
        originalPartMetaFile(canonicalPath).delete()
    }

    fun thumbnailIfCached(canonicalPath: String, oneDriveItemId: String? = null): File? {
        if (thumbExistsCache[canonicalPath] == true) {
            val cached = thumbFile(canonicalPath)
            if (cached.isFile && cached.length() > 0L && !isSyncStubPlaceholderThumbnail(cached)) {
                return cached
            }
            if (cached.isFile && isSyncStubPlaceholderThumbnail(cached)) cached.delete()
            thumbExistsCache.remove(canonicalPath)
            thumbOnDiskBySafeName.remove(safeFileName(canonicalPath))
        }
        val file = thumbFile(canonicalPath)
        if (file.isFile && file.length() > 0L) {
            if (isSyncStubPlaceholderThumbnail(file)) {
                file.delete()
                thumbExistsCache.remove(canonicalPath)
                thumbOnDiskBySafeName.remove(file.name)
            } else if (!isReadableThumbnailFile(file)) {
                file.delete()
                thumbExistsCache.remove(canonicalPath)
                thumbOnDiskBySafeName.remove(file.name)
            } else {
                rememberThumbOnDisk(canonicalPath, file)
                return file
            }
        }
        val itemId = oneDriveItemId?.takeIf { it.isNotBlank() } ?: run {
            thumbExistsCache.remove(canonicalPath)
            return null
        }
        val alias = thumbFileByRemoteItemId(itemId)
        if (!alias.isFile || alias.length() <= 0L) {
            thumbExistsCache.remove(canonicalPath)
            return null
        }
        runCatching {
            alias.inputStream().buffered().use { input ->
                file.outputStream().buffered().use { output -> input.copyTo(output) }
            }
        }
        if (file.isFile && file.length() > 0L &&
            isReadableThumbnailFile(file) &&
            !isSyncStubPlaceholderThumbnail(file)
        ) {
            rememberThumbOnDisk(canonicalPath, file)
            return file
        }
        file.delete()
        thumbExistsCache.remove(canonicalPath)
        return null
    }

    private fun isReadableThumbnailFile(file: File): Boolean {
        if (file.length() < 24L) return false
        return decodeBounds(file)?.let { (w, h) -> w > 0 && h > 0 } == true
    }

    fun isDisplayableGalleryThumbFile(file: File): Boolean = isDisplayableGalleryThumb(file)

    private val MIN_DISPLAYABLE_THUMB_BYTES = 128L

    private fun isDisplayableGalleryThumb(file: File): Boolean {
        if (isSyncStubPlaceholderThumbnail(file)) return false
        if (file.length() < MIN_DISPLAYABLE_THUMB_BYTES) return false
        val (w, h) = decodeBounds(file) ?: return false
        return w >= MIN_DISPLAYABLE_THUMB_EDGE_PX && h >= MIN_DISPLAYABLE_THUMB_EDGE_PX
    }

    private fun decodeBounds(file: File): Pair<Int, Int>? {
        return runCatching {
            val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.absolutePath, opts)
            opts.outWidth to opts.outHeight
        }.getOrNull()
    }

    /** Miniaturas commitadas no disco (exclui `.new` / staging). */
    fun countThumbFilesOnDisk(): Int {
        val files = thumbsDir.listFiles() ?: return 0
        var count = 0
        for (file in files) {
            if (!file.isFile || file.name.endsWith(".new")) continue
            if (file.length() <= 0L) continue
            if (isReadableThumbnailFile(file)) count++
        }
        return count
    }

    /** Após recriar o índice (ex.: troca de pasta na nuvem). */
    fun clearAll() {
        originalsDir.listFiles()?.forEach { it.delete() }
        thumbsDir.listFiles()?.forEach { it.delete() }
        thumbExistsCache.clear()
        thumbOnDiskBySafeName.clear()
    }

    companion object {
        const val MIN_DISPLAYABLE_THUMB_EDGE_PX = 32
        /** JPEG cinza 32×32 fica abaixo deste tamanho; miniaturas reais são maiores. */
        const val SYNC_STUB_PLACEHOLDER_MAX_BYTES = 2_048L
        /** Placeholder após falhas — maior que stub 32×32 para não ser apagado no disco. */
        const val SYNC_FAILURE_PLACEHOLDER_EDGE_PX = 96
        /** Cor neutra para placeholder de sync (evita célula totalmente preta). */
        val SYNC_STUB_PLACEHOLDER_COLOR: Int = Color.rgb(0xC4, 0xC4, 0xC4)
    }
}
