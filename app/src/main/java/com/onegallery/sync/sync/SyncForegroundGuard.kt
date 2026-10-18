package com.onegallery.sync.sync

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

/** Referência contada para iniciar/parar o serviço em primeiro plano durante tarefas longas. */
class SyncForegroundGuard(private val context: Context) {
    private val depth = AtomicInteger(0)

    fun enter(message: String? = null) {
        if (depth.incrementAndGet() == 1) {
            SyncForegroundService.start(context, message)
        }
    }

    fun leave() {
        if (depth.decrementAndGet() == 0) {
            SyncForegroundService.stop(context)
        }
    }

    suspend inline fun <T> runWithForeground(message: String? = null, block: suspend () -> T): T {
        enter(message)
        return try {
            block()
        } finally {
            leave()
        }
    }
}
