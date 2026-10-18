package com.onegallery.sync.data.onedrive

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.onegallery.sync.sync.SyncPolicy
import java.io.File

class SaOneDriveRepository(
    private val context: Context,
    private val folderPreferences: OneDriveFolderPreferences,
) {
    fun isConnected(): Boolean = folderPreferences.treeUri != null

    fun connectFolder(uri: Uri): Result<Unit> {
        val treeUri = FolderAccessUri.normalizeToTreeUri(uri)
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        return runCatching {
            context.contentResolver.takePersistableUriPermission(treeUri, flags)
            folderPreferences.treeUri = treeUri
            folderPreferences.displayName = DocumentFile.fromTreeUri(context, treeUri)?.name
                ?: DocumentFile.fromSingleUri(context, uri)?.name
        }
    }

    fun disconnect() {
        folderPreferences.clear()
    }

    fun listMirrorFiles(): Result<List<RemoteDriveFile>> {
        val root = mirrorRoot() ?: return Result.failure(IllegalStateException("Pasta OneDrive não conectada"))
        val files = mutableListOf<RemoteDriveFile>()
        listRecursive(root, "") { path, doc ->
            if (doc.isFile) {
                files.add(
                    RemoteDriveFile(
                        itemId = doc.uri.toString(),
                        name = doc.name.orEmpty(),
                        canonicalPath = path,
                        modifiedAtEpoch = doc.lastModified()?.div(1000L),
                        sizeBytes = doc.length(),
                    ),
                )
            }
        }
        return Result.success(
            files.filter { SyncPolicy.shouldIncludeLocalItem(it.canonicalPath, it.name) },
        )
    }

    fun uploadLocalFile(canonicalPath: String, sourceFile: File, mimeType: String?): Result<String> {
        val target = ensureFile(canonicalPath, mimeType) ?: return Result.failure(IllegalStateException("Não foi possível criar arquivo na nuvem"))
        return runCatching {
            context.contentResolver.openOutputStream(target.uri)?.use { out ->
                sourceFile.inputStream().use { input -> input.copyTo(out) }
            } ?: error("Sem permissão de escrita na pasta OneDrive")
            target.uri.toString()
        }
    }

    fun downloadToFile(remoteUri: String, destination: File): Result<Unit> {
        return runCatching {
            val uri = Uri.parse(remoteUri)
            destination.parentFile?.mkdirs()
            context.contentResolver.openInputStream(uri)?.use { input ->
                destination.outputStream().use { output -> input.copyTo(output) }
            } ?: error("Não foi possível ler arquivo remoto")
        }
    }

    fun deleteRemote(remoteUri: String): Result<Unit> {
        return runCatching {
            val doc = DocumentFile.fromSingleUri(context, Uri.parse(remoteUri))
                ?: error("Arquivo remoto inválido")
            if (!doc.delete()) error("Falha ao excluir na pasta OneDrive")
        }
    }

    /** Pasta que você escolheu no celular — o app OneDrive sincroniza a partir daqui (sem Azure). */
    private fun mirrorRoot(): DocumentFile? {
        val treeUri = folderPreferences.treeUri ?: return null
        return DocumentFile.fromTreeUri(context, treeUri)
    }

    private fun ensureFile(canonicalPath: String, mimeType: String?): DocumentFile? {
        val parts = canonicalPath.trim('/').split('/').filter { it.isNotBlank() }
        if (parts.isEmpty()) return null
        var current = mirrorRoot() ?: return null
        for (i in 0 until parts.lastIndex) {
            current = current.findFile(parts[i]) ?: current.createDirectory(parts[i]) ?: return null
        }
        val fileName = parts.last()
        val existing = current.findFile(fileName)
        if (existing != null && existing.isFile) return existing
        return current.createFile(mimeType ?: "application/octet-stream", fileName)
    }

    private fun listRecursive(
        dir: DocumentFile,
        prefix: String,
        onFile: (String, DocumentFile) -> Unit,
    ) {
        dir.listFiles().forEach { child ->
            val name = child.name ?: return@forEach
            val path = if (prefix.isEmpty()) name else "$prefix/$name"
            if (child.isDirectory) {
                listRecursive(child, path, onFile)
            } else if (child.isFile) {
                onFile(path, child)
            }
        }
    }
}
