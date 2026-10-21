package com.onegallery.sync.sync

import com.onegallery.sync.data.local.LocalMediaItem
import com.onegallery.sync.data.local.MediaKind
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object SyncPaths {
    /**
     * Pastas de mídia WhatsApp na raiz do espelho (um álbum por tipo: Video, Images…).
     * Subpastas (Sent, Private) entram no álbum do tipo pai — não viram álbum separado.
     */
    private val whatsAppPrimaryMediaLeaves = listOf(
        "WhatsApp Images",
        "WhatsApp Video",
        "WhatsApp Animated Gifs",
        "WhatsApp Audio",
        "WhatsApp Documents",
        "WhatsApp Stickers",
        "WhatsApp Profile Photos",
        "WhatsApp Voice Notes",
    )

    private val whatsAppKnownSubfolders = setOf("Sent", "Private")

    fun canonicalFromLocal(item: LocalMediaItem): String {
        return syncRelativePath(item.relativePath, item.displayName)
    }

    /**
     * Caminho lógico de sync (e álbum) encurtado: WhatsApp deixa de usar
     * `Android/media/com.whatsapp/WhatsApp/Media/…` e vira `WhatsApp Images/…` na raiz.
     */
    fun syncRelativePath(relativePath: String?, displayName: String): String {
        val rel = relativePath
            ?.trim('/')
            ?.replace('\\', '/')
            .orEmpty()
        val raw = if (rel.isEmpty()) displayName else "$rel/$displayName"
        return shortenWhatsAppPath(raw)
    }

    internal fun shortenWhatsAppPath(fullPath: String): String {
        val normalized = fullPath.trim('/').replace('\\', '/')
        if (!normalized.contains("whatsapp", ignoreCase = true)) {
            return normalized
        }
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return normalized
        val fileName = segments.last()

        whatsAppPrimaryMediaLeaves.firstOrNull { leaf ->
            segments.any { it.equals(leaf, ignoreCase = true) }
        }?.let { leaf ->
            return "$leaf/$fileName"
        }

        val whatsAppIndex = segments.indexOfLast { it.equals("WhatsApp", ignoreCase = true) }
        if (whatsAppIndex >= 0 && whatsAppIndex < segments.lastIndex) {
            val tail = segments.drop(whatsAppIndex)
            return if (tail.size <= 2) {
                tail.joinToString("/")
            } else {
                // Ex.: Pictures/WhatsApp/Sent → WhatsApp/Sent/arquivo
                tail.joinToString("/")
            }
        }

        return normalized
    }

    fun drivePathFromCanonical(canonicalPath: String, baseFolderPath: String = SyncPolicy.ONEDRIVE_MIRROR_ROOT): String {
        val clean = canonicalPath.trim('/').replace('\\', '/')
        val base = baseFolderPath.trim('/')
        return if (base.isEmpty()) clean else "$base/$clean"
    }

    fun canonicalFromDrivePath(driveItemPath: String?): String? {
        if (driveItemPath.isNullOrBlank()) return null
        val decoded = URLDecoder.decode(driveItemPath, StandardCharsets.UTF_8.name())
        val marker = "/${SyncPolicy.ONEDRIVE_MIRROR_ROOT}/"
        val idx = decoded.indexOf(marker, ignoreCase = true)
        if (idx < 0) return null
        return decoded.substring(idx + marker.length).trim('/')
    }

    fun albumFromCanonical(canonicalPath: String): String {
        val parts = canonicalPath.split('/')
        return if (parts.size >= 2) {
            parts.dropLast(1).joinToString("/")
        } else {
            "Raiz"
        }
    }

    /**
     * Pasta na galeria (MediaStore) ao importar/recall.
     * [storedGalleryFolder] = caminho real do aparelho (WhatsApp, DCIM…).
     * Sem pasta salva, espelha o **caminho canônico da nuvem** em `Pictures/OneGallerySync/…`
     * (mesma árvore do espelho local / OneDrive), separado da adaptação de paths do MediaStore.
     */
    fun mediaStoreRelativePathForImport(
        canonicalPath: String,
        kind: MediaKind,
        storedGalleryFolder: String? = null,
    ): String = mediaStoreImportFolderCandidates(
        canonicalPath,
        kind,
        storedGalleryFolder,
    ).first()

    /** Ordem: pasta nativa conhecida → fallback alinhado ao espelho `Pictures/OneGallerySync/…`. */
    fun mediaStoreImportFolderCandidates(
        canonicalPath: String,
        kind: MediaKind,
        storedGalleryFolder: String? = null,
    ): List<String> {
        val candidates = linkedSetOf<String>()
        storedGalleryFolder?.trim()?.takeIf { it.isNotBlank() }?.let { stored ->
            if (isDeviceGalleryRelativeFolder(stored)) {
                candidates.add(normalizeMediaStoreFolder(stored, kind))
            }
        }
        candidates.add(galleryFolderFromCanonicalForImport(canonicalPath, kind))
        return candidates.toList()
    }

    /** Caminho canônico (nuvem) → pasta MediaStore alinhada ao espelho local, salvo DCIM/Pictures/Movies nativos. */
    internal fun galleryFolderFromCanonicalForImport(canonicalPath: String, kind: MediaKind): String {
        val album = albumFromCanonical(canonicalPath).trim('/').replace('\\', '/')
        if (album.isBlank() || album.equals("Raiz", ignoreCase = true)) {
            return defaultOneGallerySyncFolder(kind)
        }
        val segments = album.split('/').filter { it.isNotBlank() }
        val primary = segments.first()
        val deviceGalleryPrimaries = when (kind) {
            MediaKind.IMAGE -> setOf("DCIM", "Pictures")
            MediaKind.VIDEO -> setOf("DCIM", "Pictures", "Movies")
        }
        if (deviceGalleryPrimaries.any { primary.equals(it, ignoreCase = true) }) {
            return mediaStoreFolderFromAlbumName(album, kind)
        }
        val mirrorRoot = SyncPolicy.ONEDRIVE_MIRROR_ROOT
        return "Pictures/$mirrorRoot/$album/"
    }

    internal fun isDeviceGalleryRelativeFolder(raw: String): Boolean {
        val folder = raw.trim().replace('\\', '/').trim('/').lowercase()
        if (folder.isBlank()) return false
        return folder.startsWith("dcim/") ||
            folder.startsWith("pictures/") ||
            folder.startsWith("movies/") ||
            folder.startsWith("android/media/")
    }

    internal fun normalizeMediaStoreFolder(raw: String, kind: MediaKind): String {
        var folder = raw.trim().replace('\\', '/').trim('/')
        if (folder.isBlank()) {
            return defaultOneGallerySyncFolder(kind)
        }
        val lastSegment = folder.substringAfterLast('/')
        if (lastSegment.contains('.') && !lastSegment.equals("Sent", ignoreCase = true)) {
            folder = folder.substringBeforeLast('/').trim('/')
        }
        if (folder.isBlank()) {
            return defaultOneGallerySyncFolder(kind)
        }
        if (folder.startsWith("Android/media/", ignoreCase = true)) {
            return "$folder/"
        }
        return mediaStoreFolderFromAlbumName(folder, kind)
    }

    /**
     * Caminho válido para [MediaStore.MediaColumns.RELATIVE_PATH] (Android 10+).
     * Pastas como "Imagens" (PT) não são raiz permitida — só DCIM/Pictures (fotos) ou DCIM/Movies/Pictures (vídeos).
     */
    private fun mediaStoreFolderFromAlbumName(albumFolder: String, kind: MediaKind): String {
        val folder = albumFolder.trim('/').replace('\\', '/')
        if (folder.isBlank() || folder.equals("Raiz", ignoreCase = true)) {
            return defaultOneGallerySyncFolder(kind)
        }
        val allowedImagePrimaries = setOf("DCIM", "Pictures")
        val allowedVideoPrimaries = setOf("DCIM", "Movies", "Pictures")
        val allowed = when (kind) {
            MediaKind.VIDEO -> allowedVideoPrimaries
            MediaKind.IMAGE -> allowedImagePrimaries
        }
        val segments = folder.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) {
            return defaultOneGallerySyncFolder(kind)
        }
        val primary = segments.first()
        val tail = segments.drop(1)
        val primaryAllowed = allowed.any { primary.equals(it, ignoreCase = true) }
        val normalizedPrimary = when {
            primary.equals("DCIM", ignoreCase = true) -> "DCIM"
            primary.equals("Pictures", ignoreCase = true) -> "Pictures"
            primary.equals("Movies", ignoreCase = true) -> "Movies"
            else -> primary
        }
        return if (primaryAllowed) {
            (listOf(normalizedPrimary) + tail).joinToString("/") + "/"
        } else {
            val root = when (kind) {
                MediaKind.VIDEO -> "Movies"
                MediaKind.IMAGE -> "Pictures"
            }
            (listOf(root) + segments).joinToString("/") + "/"
        }
    }

    private fun defaultOneGallerySyncFolder(kind: MediaKind): String {
        return when (kind) {
            MediaKind.VIDEO -> "Movies/OneGallerySync/"
            MediaKind.IMAGE -> "Pictures/OneGallerySync/"
        }
    }

    /**
     * Nome de pasta/álbum para agrupamento: usa [relativePath] (ex.: DCIM/Camera),
     * não [bucketDisplayName] do MediaStore (ex.: Camera), que duplicaria o mesmo álbum.
     */
    fun folderAlbumName(
        canonicalPath: String,
        relativePath: String? = null,
        bucketDisplayName: String = "",
    ): String {
        val rel = relativePath?.trim('/')?.replace('\\', '/').orEmpty()
        val raw = when {
            rel.isNotEmpty() -> albumFolderFromDeviceRelativePath(rel)
            else -> {
                val fromCanonical = normalizeFolderAlbumKey(albumFromCanonical(canonicalPath))
                if (fromCanonical != "Raiz") fromCanonical
                else bucketDisplayName.ifBlank { "Raiz" }
            }
        }
        return normalizeFolderAlbumKey(raw)
    }

    /**
     * Pasta do aparelho (RELATIVE_PATH): só o diretório do álbum, com WhatsApp encurtado/canônico.
     */
    private fun albumFolderFromDeviceRelativePath(relativePath: String): String {
        var folder = relativePath.trim('/').replace('\\', '/')
        val lastSegment = folder.substringAfterLast('/')
        if (lastSegment.contains('.') && !lastSegment.equals("Sent", ignoreCase = true)) {
            folder = folder.substringBeforeLast('/').trim('/')
        }
        if (folder.isBlank()) return "Raiz"
        val shortened = shortenWhatsAppPath(folder)
        val segments = shortened.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return shortened
        if (segments.size >= 2 && segments.last().contains('.')) {
            return segments.dropLast(1).joinToString("/")
        }
        if (segments.size == 2 && segments[0].equals(segments[1], ignoreCase = true)) {
            return segments[0]
        }
        return shortened
    }

    /**
     * Uma chave de álbum por pasta lógica: mesma grafia para WhatsApp (ex.: `whatsapp video` → `WhatsApp Video`).
     */
    fun normalizeFolderAlbumKey(folderAlbum: String): String {
        val normalized = folderAlbum.trim('/').replace('\\', '/')
        if (normalized.isBlank() || normalized.equals("Raiz", ignoreCase = true)) {
            return normalized.ifBlank { "Raiz" }
        }
        var segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isNotEmpty() && segments.last().contains('.')) {
            segments = segments.dropLast(1)
        }
        if (segments.isEmpty()) return "Raiz"
        val canonicalSegments = segments.map { segment ->
            whatsAppPrimaryMediaLeaves.firstOrNull { it.equals(segment, ignoreCase = true) }
                ?: segment
        }
        val collapsed = mutableListOf<String>()
        for (segment in canonicalSegments) {
            if (collapsed.lastOrNull()?.equals(segment, ignoreCase = true) == true) continue
            collapsed.add(segment)
        }
        return collapseWhatsAppAlbumGrouping(collapsed.joinToString("/"))
    }

    /**
     * Um álbum por tipo de mídia WhatsApp (ex.: só `WhatsApp Video`), mesmo com subpastas Sent/Private
     * ou caminhos legados `WhatsApp/Sent/…` no aparelho ou na nuvem.
     */
    internal fun collapseWhatsAppAlbumGrouping(folderAlbum: String): String {
        val normalized = folderAlbum.trim('/').replace('\\', '/')
        if (normalized.isBlank() || normalized.equals("Raiz", ignoreCase = true)) {
            return normalized.ifBlank { "Raiz" }
        }
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.isEmpty()) return normalized

        segments.firstOrNull { seg ->
            whatsAppPrimaryMediaLeaves.any { it.equals(seg, ignoreCase = true) }
        }?.let { hit ->
            return whatsAppPrimaryMediaLeaves.first { it.equals(hit, ignoreCase = true) }
        }

        if (segments.first().equals("WhatsApp", ignoreCase = true) && segments.size >= 2) {
            return when {
                segments[1].equals("Video", ignoreCase = true) -> "WhatsApp Video"
                else -> "WhatsApp Images"
            }
        }

        if (segments.size == 1 && whatsAppKnownSubfolders.any { it.equals(segments[0], ignoreCase = true) }) {
            return "WhatsApp Images"
        }

        return normalized
    }

    /** Título na grade de álbuns — omite o prefixo DCIM/; caminho completo fica nos detalhes. */
    fun albumListDisplayName(folderAlbum: String): String {
        val normalized = folderAlbum.trim('/').replace('\\', '/')
        if (normalized.isBlank() || normalized.equals("Raiz", ignoreCase = true)) {
            return folderAlbum.ifBlank { "Raiz" }
        }
        val segments = normalized.split('/').filter { it.isNotBlank() }
        if (segments.size >= 2 && segments.first().equals("DCIM", ignoreCase = true)) {
            return segments.drop(1).joinToString("/")
        }
        return normalized
    }
}
