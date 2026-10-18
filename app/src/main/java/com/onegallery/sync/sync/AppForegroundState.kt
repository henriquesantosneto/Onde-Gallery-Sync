package com.onegallery.sync.sync

import java.util.concurrent.atomic.AtomicBoolean

/** Indica se a UI está visível (para diálogo do MediaStore em lote). */
class AppForegroundState {
    private val inForeground = AtomicBoolean(false)

    fun setForeground(visible: Boolean) {
        inForeground.set(visible)
    }

    fun isInForeground(): Boolean = inForeground.get()
}
