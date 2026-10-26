package com.onegallery.sync.ui.gallery

import android.content.ClipData
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.onegallery.sync.data.local.MediaKind
import java.io.File

internal enum class ShareLocalKind {
    CONTENT,
    MEDIA_ID,
    CACHE_FILE,
    NONE,
}

/** Ordem: arquivo da galeria, id do MediaStore, cópia em cache. Nuvem sem arquivo fica de fora. */
internal fun shareLocalKind(item: GalleryDisplayItem): ShareLocalKind {
    if (!item.localReleasedForSpace) {
        if (item.localUri?.scheme == "content") return ShareLocalKind.CONTENT
        if (item.localMediaId != null) return ShareLocalKind.MEDIA_ID
    }
    val cached = item.cachedOriginal
    if (cached != null && cached.isFile && cached.length() > 0L) return ShareLocalKind.CACHE_FILE
    return ShareLocalKind.NONE
}

internal fun shareIntentMime(kinds: List<MediaKind>): String {
    val distinct = kinds.distinct()
    return when {
        distinct.size != 1 -> "*/*"
        distinct.single() == MediaKind.VIDEO -> "video/*"
        else -> "image/*"
    }
}

/**
 * Abre o seletor do Android (WhatsApp e os demais).
 * @return aviso quando algum item ficou de fora ou nada pôde ser enviado; null se tudo foi adiante.
 */
fun shareGalleryMedia(
    context: Context,
    paths: Collection<String>,
    pools: List<List<GalleryDisplayItem>>,
): String? {
    if (paths.isEmpty()) return "Selecione uma mídia para compartilhar."
    val byPath = LinkedHashMap<String, GalleryDisplayItem>()
    for (pool in pools) {
        for (item in pool) {
            byPath.putIfAbsent(item.canonicalPath, item)
        }
    }
    val chosen = paths.mapNotNull { byPath[it] }
    val sharable = chosen.mapNotNull { item ->
        resolveShareUri(context, item)?.let { uri -> item to uri }
    }
    if (sharable.isEmpty()) {
        return "Nada no aparelho para compartilhar. O arquivo ainda está só na nuvem."
    }
    val launched = runCatching {
        launchShareChooser(
            context,
            sharable.map { (item, uri) -> uri to item.kind },
        )
    }.isSuccess
    if (!launched) return "Não foi possível abrir o compartilhamento."
    val skipped = paths.size - sharable.size
    return if (skipped > 0) {
        "$skipped item(ns) sem cópia no aparelho não foram incluídos."
    } else {
        null
    }
}

private fun resolveShareUri(context: Context, item: GalleryDisplayItem): Uri? {
    if (!item.localReleasedForSpace) {
        item.localUri?.takeIf { it.scheme == "content" && uriExistsRouter.uriExists(context, it) }
            ?.let { return it }
        item.localMediaId?.let { id ->
            val base = if (item.kind == MediaKind.VIDEO) {
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val uri = ContentUris.withAppendedId(base, id)
            if (uriExistsRouter.uriExists(context, uri)) return uri
        }
    }
    val file = item.cachedOriginal?.takeIf { it.isFile && it.length() > 0L } ?: return null
    return fileProviderUri(context, file)
}

private fun fileProviderUri(context: Context, file: File): Uri? {
    return runCatching {
        FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file,
        )
    }.getOrNull()
}

private fun launchShareChooser(context: Context, parts: List<Pair<Uri, MediaKind>>) {
    val uris = parts.map { it.first }
    val mime = shareIntentMime(parts.map { it.second })
    val send = Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
        type = mime
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        if (uris.size == 1) {
            putExtra(Intent.EXTRA_STREAM, uris.first())
        } else {
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        val clip = ClipData.newUri(context.contentResolver, "OneGallery", uris.first())
        for (uri in uris.drop(1)) {
            clip.addItem(ClipData.Item(uri))
        }
        clipData = clip
    }
    val resolved = context.packageManager.queryIntentActivities(send, PackageManager.MATCH_DEFAULT_ONLY)
    for (info in resolved) {
        val packageName = info.activityInfo.packageName
        for (uri in uris) {
            context.grantUriPermission(packageName, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    val chooser = Intent.createChooser(send, "Compartilhar").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(chooser)
}
