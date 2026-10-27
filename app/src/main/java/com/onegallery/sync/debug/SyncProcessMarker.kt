package com.onegallery.sync.debug

import java.io.File
import java.io.FileOutputStream

/**
 * O processo pode morrer no sincronismo sem exceção Java (memória ou decodificador nativo).
 * O último passo fica neste arquivo. Na abertura seguinte vira o relatório de fechamento
 * se a tela ainda estava visível.
 */
object SyncProcessMarker {
    private const val FILE_NAME = "sync-inflight.txt"

    @Volatile
    private var lastPayload: String? = null

    fun note(filesDir: File, foreground: Boolean, step: String) {
        val clean = step.replace('\n', ' ').trim().take(500)
        if (clean.isEmpty()) return
        val payload = "foreground=$foreground\nstep=$clean\n"
        if (payload == lastPayload) return
        lastPayload = payload
        val dest = File(filesDir, FILE_NAME)
        val tmp = File(filesDir, "$FILE_NAME.tmp")
        runCatching {
            FileOutputStream(tmp).use { out ->
                out.write(payload.toByteArray(Charsets.UTF_8))
                out.fd.sync()
            }
            if (!tmp.renameTo(dest)) {
                dest.writeText(payload)
                tmp.delete()
            }
        }
    }

    /** A tela saiu. Morte em segundo plano não vira relatório de fechamento. */
    fun setForeground(filesDir: File, foreground: Boolean) {
        val parsed = parse(lastPayload) ?: parse(
            runCatching { File(filesDir, FILE_NAME).takeIf { it.isFile }?.readText() }.getOrNull(),
        ) ?: return
        if (parsed.foreground == foreground) return
        note(filesDir, foreground, parsed.step)
    }

    fun clear(filesDir: File) {
        lastPayload = null
        runCatching {
            File(filesDir, FILE_NAME).delete()
            File(filesDir, "$FILE_NAME.tmp").delete()
        }
    }

    /**
     * Se o processo caiu com a tela aberta, grava [DebugCrashLog] e apaga o marcador.
     * Devolve o relatório criado, ou null quando não havia queda para mostrar.
     */
    fun promoteUncleanExit(
        filesDir: File,
        versionName: String,
        versionCode: Int,
    ): String? {
        val file = File(filesDir, FILE_NAME)
        val raw = runCatching { if (file.isFile) file.readText() else null }.getOrNull()
        clear(filesDir)
        val parsed = parse(raw) ?: return null
        if (!parsed.foreground) return null
        if (DebugCrashLog.read(filesDir) != null) return null
        val report = uncleanSyncReport(
            versionName = versionName,
            versionCode = versionCode,
            step = parsed.step,
            whenEpochMs = System.currentTimeMillis(),
        )
        DebugCrashLog.write(filesDir, report)
        return report
    }

    internal fun parse(raw: String?): Marker? {
        if (raw.isNullOrBlank()) return null
        var foreground = false
        var step = ""
        for (line in raw.lineSequence()) {
            when {
                line.startsWith("foreground=") -> foreground = line.removePrefix("foreground=") == "true"
                line.startsWith("step=") -> step = line.removePrefix("step=").trim()
            }
        }
        if (step.isEmpty()) return null
        return Marker(foreground, step)
    }

    internal fun uncleanSyncReport(
        versionName: String,
        versionCode: Int,
        step: String,
        whenEpochMs: Long,
    ): String = buildString {
        append("OneGallery Sync ")
        append(versionName)
        append(" (")
        append(versionCode)
        append(")\n")
        append("thread: processo encerrado pelo sistema\n")
        append("quando: ")
        append(whenEpochMs)
        append("\n\n")
        append("O app fechou a tela sozinho durante o sincronismo.\n")
        append("O Android não entregou uma exceção Java (memória ou decodificador de vídeo).\n\n")
        append("Último passo:\n")
        append(step)
        append('\n')
    }

    internal data class Marker(val foreground: Boolean, val step: String)
}
