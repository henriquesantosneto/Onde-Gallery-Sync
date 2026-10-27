package com.onegallery.sync.sync

enum class PendingDeleteTarget {
    NONE,
    LOCAL,
    REMOTE,
    BOTH,
}

/** O usuário desistiu no diálogo do Android. A galeria volta a mostrar o item. */
class DeleteCancelledException(message: String) : IllegalStateException(message)
