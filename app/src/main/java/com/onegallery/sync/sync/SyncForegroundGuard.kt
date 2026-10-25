package com.onegallery.sync.sync

import android.content.Context
import java.util.concurrent.atomic.AtomicInteger

/** Referência contada para iniciar/parar o serviço em primeiro plano durante tarefas longas. */
class SyncForegroundGuard(private val context: Context) {
    private val depth = AtomicInteger(0)

    fun holdsForeground(): Boolean = depth.get() > 0

    fun enter(message: String? = null) {
        val next = depth.incrementAndGet()
        if (next == 1 || message != null) {
            SyncForegroundService.start(context, message)
        }
    }

    fun leave(detachNotification: Boolean = false) {
        while (true) {
            val current = depth.get()
            if (current <= 0) return
            if (!depth.compareAndSet(current, current - 1)) continue
            if (current == 1) {
                SyncForegroundService.stop(
                    context,
                    detachNotification = detachNotification,
                ) { depth.get() == 0 }
            }
            return
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
