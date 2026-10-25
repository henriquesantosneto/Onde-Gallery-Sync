package com.onegallery.sync.debug

import android.app.Application
import android.content.Context
import android.util.Log
import com.onegallery.sync.BuildConfig
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.util.ArrayDeque

/**
 * Guarda o último fechamento inesperado e um rastro curto do que a grade estava fazendo.
 * Queda nativa (vídeo/memória fora do Java) não passa por aqui.
 */
object DebugCrashLog {
    private const val FILE_NAME = "last-crash.txt"
    private const val MAX_BREADCRUMBS = 30
    private const val MAX_REPORT_CHARS = 80_000

    private val breadcrumbs = ArrayDeque<String>()
    private val breadcrumbLock = Any()

    @Volatile
    private var reporting = false

    fun install(app: Application) {
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            if (reporting) {
                previous?.uncaughtException(thread, error)
                return@setDefaultUncaughtExceptionHandler
            }
            reporting = true
            runCatching {
                val short = "${error.javaClass.name}: ${error.message}"
                write(app.filesDir, "OneGallery fechou\n$short\n")
                val report = buildReport(
                    versionName = BuildConfig.VERSION_NAME,
                    versionCode = BuildConfig.VERSION_CODE,
                    threadName = thread.name,
                    error = error,
                    breadcrumbs = snapshotBreadcrumbs(),
                )
                write(app.filesDir, report)
                Log.e("OneGalleryCrash", report)
            }
            previous?.uncaughtException(thread, error)
        }
    }

    fun mark(message: String) {
        if (message.isBlank()) return
        val line = "${System.currentTimeMillis()} $message"
        synchronized(breadcrumbLock) {
            if (breadcrumbs.size >= MAX_BREADCRUMBS) breadcrumbs.removeFirst()
            breadcrumbs.addLast(line)
        }
    }

    fun read(context: Context): String? = read(context.filesDir)

    fun clear(context: Context) {
        clear(context.filesDir)
    }

    internal fun read(filesDir: File): String? {
        val file = File(filesDir, FILE_NAME)
        if (!file.isFile || file.length() <= 0L) return null
        return runCatching { file.readText().take(MAX_REPORT_CHARS) }.getOrNull()
    }

    internal fun clear(filesDir: File) {
        runCatching { File(filesDir, FILE_NAME).delete() }
    }

    internal fun write(filesDir: File, report: String) {
        val dest = File(filesDir, FILE_NAME)
        val tmp = File(filesDir, "$FILE_NAME.tmp")
        tmp.writeText(report.take(MAX_REPORT_CHARS))
        if (dest.exists() && !dest.delete()) {
            dest.writeText(tmp.readText())
            tmp.delete()
            return
        }
        if (!tmp.renameTo(dest)) {
            dest.writeText(tmp.readText())
            tmp.delete()
        }
    }

    internal fun buildReport(
        versionName: String,
        versionCode: Int,
        threadName: String,
        error: Throwable,
        breadcrumbs: List<String>,
    ): String {
        val trace = StringWriter()
        error.printStackTrace(PrintWriter(trace))
        return buildString {
            append("OneGallery Sync ")
            append(versionName)
            append(" (")
            append(versionCode)
            append(")\n")
            append("thread: ")
            append(threadName)
            append('\n')
            append("quando: ")
            append(System.currentTimeMillis())
            append("\n\n")
            append("rastro:\n")
            if (breadcrumbs.isEmpty()) {
                append("(vazio)\n")
            } else {
                breadcrumbs.forEach { line ->
                    append(line)
                    append('\n')
                }
            }
            append("\nerro:\n")
            append(trace.toString())
        }
    }

    private fun snapshotBreadcrumbs(): List<String> = synchronized(breadcrumbLock) {
        breadcrumbs.toList()
    }
}
