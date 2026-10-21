package com.onegallery.sync.sync

import com.onegallery.sync.data.local.LocalMediaItem
import com.onegallery.sync.data.local.MediaKind
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object SyncPaths {
    /** Pastas conhecidas do WhatsApp (Android/media/.../Media/…). Ficam na raiz do espelho OneGallerySync. */
    private val whatsAppMediaLeafFolders = listOf(
        "WhatsApp Images",
        "WhatsApp Video",
        "WhatsApp Animated Gifs",
        "WhatsApp Audio",
        "WhatsApp Documents",
        "WhatsApp Stickers",
        "WhatsApp Profile Photos",
        "WhatsApp Voice Notes",
        "Private",
        "Sent",
    )

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

        whatsAppMediaLeafFolders.firstOrNull { leaf ->
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
        if (rel.isNotEmpty()) {
            return shortenWhatsAppPath(rel)
        }
        val fromCanonical = albumFromCanonical(canonicalPath)
        if (fromCanonical != "Raiz") return fromCanonical
        return bucketDisplayName.ifBlank { "Raiz" }
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
