package com.onegallery.sync.data.onedrive

import android.content.Context
import android.os.Environment
import com.onegallery.sync.sync.SyncPolicy
import java.io.File

/**
 * Espelha arquivos em Pictures/OneGallerySync no armazenamento do telefone.
 * O app Microsoft OneDrive (backup de pastas) pode sincronizar essa pasta para a nuvem
 * sem Azure, sem seletor SAF e sem Client ID.
 */
class LocalMirrorRepository(private val context: Context) {

    private val mirrorRoot: File
        get() {
            val base = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES)
            return File(base, SyncPolicy.ONEDRIVE_MIRROR_ROOT).apply { mkdirs() }
        }

    fun isEnabled(): Boolean = mirrorRoot.exists()

    fun rootPathLabel(): String = mirrorRoot.absolutePath

    fun listMirrorFiles(): Result<List<RemoteDriveFile>> {
        val root = mirrorRoot
        if (!root.exists()) return Result.success(emptyList())
        val files = mutableListOf<RemoteDriveFile>()
        listRecursive(root, root, files)
        return Result.success(
            files.filter { SyncPolicy.shouldIncludeLocalItem(it.canonicalPath, it.name) },
        )
    }

    fun uploadLocalFile(canonicalPath: String, sourceFile: File, @Suppress("UNUSED_PARAMETER") mimeType: String?): Result<String> {
        val target = ensureFile(canonicalPath) ?: return Result.failure(IllegalStateException("Não foi possível criar arquivo local"))
        return runCatching {
            sourceFile.inputStream().use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            target.absolutePath
        }
    }

    fun downloadToFile(remoteId: String, destination: File): Result<Unit> {
        return runCatching {
            val source = File(remoteId)
            if (!source.exists()) error("Arquivo não encontrado")
            destination.parentFile?.mkdirs()
            source.inputStream().use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }

    fun deleteRemote(remoteId: String): Result<Unit> {
        return runCatching {
            if (!File(remoteId).delete()) error("Falha ao excluir arquivo local")
        }
    }

    private fun listRecursive(root: File, dir: File, out: MutableList<RemoteDriveFile>) {
        dir.listFiles()?.forEach { child ->
            if (child.isDirectory) {
                listRecursive(root, child, out)
            } else if (child.isFile) {
                val canonical = root.toURI().relativize(child.toURI()).path.trim('/')
                out.add(
                    RemoteDriveFile(
                        itemId = child.absolutePath,
                        name = child.name,
                        canonicalPath = canonical,
                        modifiedAtEpoch = child.lastModified() / 1000L,
                        sizeBytes = child.length(),
                    ),
                )
            }
        }
    }

    private fun ensureFile(canonicalPath: String): File? {
        val parts = canonicalPath.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        var current: File = mirrorRoot
        for (i in 0 until parts.lastIndex) {
            current = File(current, parts[i])
            if (!current.exists()) current.mkdirs()
        }
        return File(current, parts.last())
    }
}
