package com.onegallery.sync.sync

import com.onegallery.sync.data.local.LocalMediaItem
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
}
