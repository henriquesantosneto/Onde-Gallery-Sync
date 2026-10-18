package com.onegallery.sync.sync

import com.onegallery.sync.data.local.LocalMediaItem
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

object SyncPaths {
    fun canonicalFromLocal(item: LocalMediaItem): String {
        val rel = item.relativePath
            ?.trim('/')
            ?.replace('\\', '/')
            .orEmpty()
        return if (rel.isEmpty()) item.displayName else "$rel/${item.displayName}"
    }

    fun drivePathFromCanonical(canonicalPath: String): String {
        val clean = canonicalPath.trim('/').replace('\\', '/')
        return "${SyncPolicy.ONEDRIVE_MIRROR_ROOT}/$clean"
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
}
