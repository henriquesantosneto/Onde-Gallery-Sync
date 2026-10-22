package com.onegallery.sync.data.local.db

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * Emite uma cópia em memória do índice antes da 1ª emissão pesada do Room `observeAll`,
 * para a galeria não ficar vazia à espera do sync.
 */
class GalleryMetadataBootstrap(
    private val scope: CoroutineScope,
) {
    @Volatile
    private var snapshot: List<SyncMetadataEntity>? = null

    private var initialEmitDone = false
    private val mutex = Mutex()

    fun startLoading(dao: SyncMetadataDao) {
        scope.launch(Dispatchers.IO) {
            runCatching {
                snapshot = dao.getAll()
            }
        }
    }

    /** Uma emissão inicial — aguarda [getAll] até [INITIAL_WAIT_MS] para não pintar galeria vazia. */
    fun initialMetadataFlow(): Flow<List<SyncMetadataEntity>> = flow {
        val cached = withContext(Dispatchers.IO) {
            var waitedMs = 0L
            while (snapshot == null && waitedMs < INITIAL_WAIT_MS) {
                delay(POLL_MS)
                waitedMs += POLL_MS
            }
            snapshot
        }
        mutex.withLock {
            if (!initialEmitDone) {
                initialEmitDone = true
                emit(cached ?: emptyList())
            }
        }
    }

    suspend fun peekSnapshot(): List<SyncMetadataEntity>? = awaitSnapshot()

    private suspend fun awaitSnapshot(): List<SyncMetadataEntity>? {
        snapshot?.let { return it }
        return withContext(Dispatchers.IO) {
            mutex.withLock { snapshot }
        }
    }

    companion object {
        private const val INITIAL_WAIT_MS = 8_000
        private const val POLL_MS = 40L
    }
}
