package com.onegallery.sync.data.onedrive

import android.content.Context
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.io.File
import java.security.MessageDigest

class UploadSessionStore(context: Context) {
    private val dir = File(context.cacheDir, "transfer_state/upload").apply { mkdirs() }
    private val adapter = Moshi.Builder()
        .add(KotlinJsonAdapterFactory())
        .build()
        .adapter(PersistedUploadSession::class.java)

    fun load(canonicalPath: String): PersistedUploadSession? {
        val file = stateFile(canonicalPath)
        if (!file.isFile) return null
        return runCatching { adapter.fromJson(file.readText()) }.getOrNull()
    }

    fun save(session: PersistedUploadSession) {
        stateFile(session.canonicalPath).writeText(adapter.toJson(session))
    }

    fun clear(canonicalPath: String) {
        stateFile(canonicalPath).delete()
    }

    private fun stateFile(canonicalPath: String): File {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(canonicalPath.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return File(dir, "$digest.json")
    }
}
