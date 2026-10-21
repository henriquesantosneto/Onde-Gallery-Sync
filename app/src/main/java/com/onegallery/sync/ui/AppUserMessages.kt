package com.onegallery.sync.ui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/** Toasts/snackbars de ações do usuário (exclusão, mover, etc.) — exibidos na raiz do app. */
class AppUserMessages {
    private val _events = MutableSharedFlow<String>(extraBufferCapacity = 16)
    val events: SharedFlow<String> = _events.asSharedFlow()

    fun show(message: String) {
        val text = message.trim()
        if (text.isNotEmpty()) {
            _events.tryEmit(text)
        }
    }
}
