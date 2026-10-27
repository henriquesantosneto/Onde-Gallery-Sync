package com.onegallery.sync.sync

import android.content.Context
import com.onegallery.sync.data.cloud.CloudProvider
import com.onegallery.sync.data.local.db.SyncIndexStatsRow

class SyncPreferences(context: Context) {
    private val prefs = context.getSharedPreferences("onegallery_sync", Context.MODE_PRIVATE)
    private val thumbFailLock = Any()

    fun migrateForAppVersion(currentVersionCode: Int) {
        val last = prefs.getInt(KEY_LAST_VERSION, 0)
        if (last < currentVersionCode) {
            val editor = prefs.edit().putInt(KEY_LAST_VERSION, currentVersionCode)
            if (last in 1 until 362) {
                editor.putBoolean(KEY_FORCE_REMOTE_FULL_LISTING, true)
            }
            editor.apply()
        }
    }

    /** Próximo sync Graph faz varredura recursiva completa (corrige índice menor que a nuvem). */
    var forceRemoteFullListingOnce: Boolean
        get() = prefs.getBoolean(KEY_FORCE_REMOTE_FULL_LISTING, false)
        set(value) = prefs.edit().putBoolean(KEY_FORCE_REMOTE_FULL_LISTING, value).apply()

    /**
     * Corrige instalações que perderam o modo de sync após migrações antigas,
     * mas ainda têm pasta na nuvem ou pasta local salva.
     */
    fun repairPersistedConnection(
        graphFolderConfigured: Boolean,
        localOneDriveFolderConnected: Boolean,
    ) {
        when {
            graphFolderConfigured && syncMode != SyncMode.ONEDRIVE_GRAPH -> {
                syncMode = SyncMode.ONEDRIVE_GRAPH
                hasCompletedSetup = true
            }
            localOneDriveFolderConnected && syncMode == SyncMode.LOCAL_ONLY -> {
                syncMode = SyncMode.ONEDRIVE_FOLDER
                hasCompletedSetup = true
            }
        }
    }

    var wifiOnly: Boolean
        get() = prefs.getBoolean(KEY_WIFI_ONLY, true)
        set(value) = prefs.edit().putBoolean(KEY_WIFI_ONLY, value).apply()

    /**
     * Sync automático contínuo: WorkManager periódico, boot, rede e mudanças no MediaStore.
     * Desligar deixa só sync manual e ao abrir o app.
     */
    var backgroundSyncAlwaysEnabled: Boolean
        get() = prefs.getBoolean(KEY_BACKGROUND_SYNC_ALWAYS, true)
        set(value) = prefs.edit().putBoolean(KEY_BACKGROUND_SYNC_ALWAYS, value).apply()

    /** Miniaturas em 1º plano com mais paralelismo e levas maiores (padrão ligado). */
    var aggressiveThumbnailPrefetch: Boolean
        get() = prefs.getBoolean(KEY_AGGRESSIVE_THUMB_PREFETCH, true)
        set(value) = prefs.edit().putBoolean(KEY_AGGRESSIVE_THUMB_PREFETCH, value).apply()

    var hasCompletedSetup: Boolean
        get() = prefs.getBoolean(KEY_SETUP_DONE, false)
        set(value) = prefs.edit().putBoolean(KEY_SETUP_DONE, value).apply()

    var syncMode: SyncMode
        get() = SyncMode.valueOf(prefs.getString(KEY_SYNC_MODE, SyncMode.LOCAL_ONLY.name)!!)
        set(value) = prefs.edit().putString(KEY_SYNC_MODE, value.name).apply()

    /** Provedor API (Graph): OneDrive ou Dropbox futuro. Modos SAF/espelho ignoram. */
    var cloudProvider: CloudProvider
        get() = CloudProvider.valueOf(
            prefs.getString(KEY_CLOUD_PROVIDER, CloudProvider.ONEDRIVE.name)!!,
        )
        set(value) = prefs.edit().putString(KEY_CLOUD_PROVIDER, value.name).apply()

    /** Se a mídia sumir do aparelho, apaga também no OneDrive (sem pedir confirmação). */
    var autoDeleteRemoteWhenLocalRemoved: Boolean
        get() = prefs.getBoolean(KEY_AUTO_DELETE_REMOTE, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_DELETE_REMOTE, value).apply()

    /**
     * Logo após backup no OneDrive, remove a cópia local e mantém miniatura + arquivo na nuvem.
     * Downloads no sync continuam só com miniatura até você abrir o item.
     */
    var autoFreeLocalSpaceWhenSynced: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FREE_LOCAL, true)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FREE_LOCAL, value).apply()

    /** Remove cópia local / cache do original após [localCopyIdleReleaseDays] sem abrir no app. */
    var autoFreeLocalSpaceWhenIdle: Boolean
        get() = prefs.getBoolean(KEY_AUTO_FREE_IDLE, false)
        set(value) = prefs.edit().putBoolean(KEY_AUTO_FREE_IDLE, value).apply()

    /** Dias sem abrir a foto no app antes da liberação por inatividade (1–180). */
    var localCopyIdleReleaseDays: Int
        get() = prefs.getInt(KEY_LOCAL_IDLE_RELEASE_DAYS, DEFAULT_LOCAL_IDLE_RELEASE_DAYS)
            .coerceIn(MIN_LOCAL_IDLE_RELEASE_DAYS, MAX_LOCAL_IDLE_RELEASE_DAYS)
        set(value) = prefs.edit()
            .putInt(
                KEY_LOCAL_IDLE_RELEASE_DAYS,
                value.coerceIn(MIN_LOCAL_IDLE_RELEASE_DAYS, MAX_LOCAL_IDLE_RELEASE_DAYS),
            )
            .apply()

    /** Pausa sync/index automáticos após o usuário cancelar (epoch ms). */
    var autoSyncPausedUntilMs: Long
        get() = prefs.getLong(KEY_AUTO_SYNC_PAUSED_UNTIL, 0L)
        private set(value) = prefs.edit().putLong(KEY_AUTO_SYNC_PAUSED_UNTIL, value).apply()

    fun pauseAutoSyncAfterUserCancel(durationMs: Long = CANCEL_COOLDOWN_MS) {
        autoSyncPausedUntilMs = System.currentTimeMillis() + durationMs
    }

    fun autoSyncPausedRemainingMs(): Long {
        return (autoSyncPausedUntilMs - System.currentTimeMillis()).coerceAtLeast(0L)
    }

    fun isAutoSyncPaused(): Boolean = autoSyncPausedRemainingMs() > 0

    var lastBidirectionalSyncCompletedAtMs: Long
        get() = prefs.getLong(KEY_LAST_BIDIR_SYNC_DONE_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_LAST_BIDIR_SYNC_DONE_MS, value).apply()

    /**
     * Atualizado ao fim do sync (e no [SyncWorker]) para gatilhos em background
     * respeitarem retry curto quando ainda há envios/downloads na fila.
     */
    var cachedPendingTransferCount: Int
        get() = prefs.getInt(KEY_CACHED_PENDING_TRANSFER, 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_CACHED_PENDING_TRANSFER, value.coerceAtLeast(0)).apply()

    /** Round-robin no backfill de GPS para não ficar reprovando sempre os mesmos itens. */
    var gpsBackfillCursor: Int
        get() = prefs.getInt(KEY_GPS_BACKFILL_CURSOR, 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_GPS_BACKFILL_CURSOR, value.coerceAtLeast(0)).apply()

    /** Evita que a fila de miniaturas mostre sempre os mesmos nomes no topo. */
    var thumbPrefetchCursor: Int
        get() = prefs.getInt(KEY_THUMB_PREFETCH_CURSOR, 0).coerceAtLeast(0)
        set(value) = prefs.edit().putInt(KEY_THUMB_PREFETCH_CURSOR, value.coerceAtLeast(0)).apply()

    fun thumbnailFailureCount(canonicalPath: String): Int = synchronized(thumbFailLock) {
        val prefix = thumbnailFailKey(canonicalPath)
        val set = prefs.getStringSet(KEY_THUMB_FAIL_COUNTS, emptySet()) ?: emptySet()
        set.firstOrNull { it.startsWith(prefix) }
            ?.substringAfter("::")
            ?.toIntOrNull()
            ?: 0
    }

    fun incrementThumbnailFailure(canonicalPath: String): Int = synchronized(thumbFailLock) {
        val next = thumbnailFailureCountUnlocked(canonicalPath) + 1
        val prefix = thumbnailFailKey(canonicalPath)
        val set = (prefs.getStringSet(KEY_THUMB_FAIL_COUNTS, emptySet()) ?: emptySet()).toMutableSet()
        set.removeAll { it.startsWith(prefix) }
        set.add("$prefix$next")
        prefs.edit().putStringSet(KEY_THUMB_FAIL_COUNTS, set).apply()
        next
    }

    fun clearThumbnailFailure(canonicalPath: String) = synchronized(thumbFailLock) {
        val prefix = thumbnailFailKey(canonicalPath)
        val set = (prefs.getStringSet(KEY_THUMB_FAIL_COUNTS, emptySet()) ?: emptySet()).toMutableSet()
        if (set.removeAll { it.startsWith(prefix) }) {
            prefs.edit().putStringSet(KEY_THUMB_FAIL_COUNTS, set).apply()
        }
    }

    private fun thumbnailFailureCountUnlocked(canonicalPath: String): Int {
        val prefix = thumbnailFailKey(canonicalPath)
        val set = prefs.getStringSet(KEY_THUMB_FAIL_COUNTS, emptySet()) ?: emptySet()
        return set.firstOrNull { it.startsWith(prefix) }
            ?.substringAfter("::")
            ?.toIntOrNull()
            ?: 0
    }

    fun clearAllThumbnailFailureCounts() = synchronized(thumbFailLock) {
        prefs.edit().remove(KEY_THUMB_FAIL_COUNTS).apply()
    }

    /** Cursores e contadores ligados ao índice antigo — após zerar e reindexar. */
    fun resetIndexMaintenanceState() = synchronized(thumbFailLock) {
        prefs.edit()
            .putInt(KEY_GPS_BACKFILL_CURSOR, 0)
            .putInt(KEY_THUMB_PREFETCH_CURSOR, 0)
            .remove(KEY_THUMB_FAIL_COUNTS)
            .apply()
    }

    private fun thumbnailFailKey(canonicalPath: String): String = "$canonicalPath::"

    var mediaLibraryWatchSuppressedUntilMs: Long
        get() = prefs.getLong(KEY_MEDIA_WATCH_SUPPRESS_UNTIL_MS, 0L)
        private set(value) = prefs.edit().putLong(KEY_MEDIA_WATCH_SUPPRESS_UNTIL_MS, value).apply()

    fun extendMediaLibraryWatchSuppression(additionalMs: Long) {
        val until = System.currentTimeMillis() + additionalMs
        if (until > mediaLibraryWatchSuppressedUntilMs) {
            mediaLibraryWatchSuppressedUntilMs = until
        }
    }

    fun isMediaLibraryWatchSuppressed(): Boolean =
        System.currentTimeMillis() < mediaLibraryWatchSuppressedUntilMs

    /** Intervalo mínimo antes de outro sync automático (menor se ainda há transferências pendentes). */
    fun autoBidirectionalMinIntervalMs(): Long {
        return if (cachedPendingTransferCount > 0) {
            AUTO_BIDIRECTIONAL_UPLOAD_RETRY_MS
        } else {
            AUTO_BIDIRECTIONAL_MIN_INTERVAL_MS
        }
    }

    /**
     * Quanto falta até um sync automático poder rodar (0 = já pode).
     * Usado para agendar WorkManager em vez de descartar o gatilho.
     */
    fun millisUntilBackgroundSyncAllowed(): Long {
        val pauseMs = autoSyncPausedRemainingMs()
        if (pauseMs > 0L) return pauseMs
        val now = System.currentTimeMillis()
        if (isMediaLibraryWatchSuppressed()) {
            return (mediaLibraryWatchSuppressedUntilMs - now).coerceAtLeast(0L)
        }
        val last = lastBidirectionalSyncCompletedAtMs
        if (last <= 0L) return 0L
        val elapsed = now - last
        val need = autoBidirectionalMinIntervalMs()
        return (need - elapsed).coerceAtLeast(0L)
    }

    /** Evita rajadas de sync automático (WorkManager, rede, MediaStore). */
    fun shouldDeferBackgroundSyncByTime(): Boolean = millisUntilBackgroundSyncAllowed() > 0L

    /** Último snapshot das estatísticas do índice (Ajustes / Sync). */
    fun cachedIndexStats(): SyncIndexStatsRow? {
        if (!prefs.getBoolean(KEY_INDEX_STATS_CACHED, false)) return null
        return SyncIndexStatsRow(
            total = prefs.getInt(KEY_INDEX_STATS_TOTAL, 0),
            withLocal = prefs.getInt(KEY_INDEX_STATS_WITH_LOCAL, 0),
            synced = prefs.getInt(KEY_INDEX_STATS_SYNCED, 0),
            pendingUpload = prefs.getInt(KEY_INDEX_STATS_PENDING_UPLOAD, 0),
            pendingDownload = prefs.getInt(KEY_INDEX_STATS_PENDING_DOWNLOAD, 0),
            cloudOnly = prefs.getInt(KEY_INDEX_STATS_CLOUD_ONLY, 0),
            localOnly = prefs.getInt(KEY_INDEX_STATS_LOCAL_ONLY, 0),
            errors = prefs.getInt(KEY_INDEX_STATS_ERRORS, 0),
            conflicts = prefs.getInt(KEY_INDEX_STATS_CONFLICTS, 0),
            attention = prefs.getInt(KEY_INDEX_STATS_ATTENTION, 0),
            images = prefs.getInt(KEY_INDEX_STATS_IMAGES, 0),
            videos = prefs.getInt(KEY_INDEX_STATS_VIDEOS, 0),
            audio = prefs.getInt(KEY_INDEX_STATS_AUDIO, 0),
            documents = prefs.getInt(KEY_INDEX_STATS_DOCUMENTS, 0),
            otherTypes = prefs.getInt(KEY_INDEX_STATS_OTHER_TYPES, 0),
            totalBytes = prefs.getLong(KEY_INDEX_STATS_TOTAL_BYTES, 0L),
            imageBytes = prefs.getLong(KEY_INDEX_STATS_IMAGE_BYTES, 0L),
            videoBytes = prefs.getLong(KEY_INDEX_STATS_VIDEO_BYTES, 0L),
            audioBytes = prefs.getLong(KEY_INDEX_STATS_AUDIO_BYTES, 0L),
            documentBytes = prefs.getLong(KEY_INDEX_STATS_DOCUMENT_BYTES, 0L),
            otherTypeBytes = prefs.getLong(KEY_INDEX_STATS_OTHER_TYPE_BYTES, 0L),
        )
    }

    fun saveCachedIndexStats(row: SyncIndexStatsRow) {
        prefs.edit()
            .putBoolean(KEY_INDEX_STATS_CACHED, true)
            .putInt(KEY_INDEX_STATS_TOTAL, row.total)
            .putInt(KEY_INDEX_STATS_WITH_LOCAL, row.withLocal)
            .putInt(KEY_INDEX_STATS_SYNCED, row.synced)
            .putInt(KEY_INDEX_STATS_PENDING_UPLOAD, row.pendingUpload)
            .putInt(KEY_INDEX_STATS_PENDING_DOWNLOAD, row.pendingDownload)
            .putInt(KEY_INDEX_STATS_CLOUD_ONLY, row.cloudOnly)
            .putInt(KEY_INDEX_STATS_LOCAL_ONLY, row.localOnly)
            .putInt(KEY_INDEX_STATS_ERRORS, row.errors)
            .putInt(KEY_INDEX_STATS_CONFLICTS, row.conflicts)
            .putInt(KEY_INDEX_STATS_ATTENTION, row.attention)
            .putInt(KEY_INDEX_STATS_IMAGES, row.images)
            .putInt(KEY_INDEX_STATS_VIDEOS, row.videos)
            .putInt(KEY_INDEX_STATS_AUDIO, row.audio)
            .putInt(KEY_INDEX_STATS_DOCUMENTS, row.documents)
            .putInt(KEY_INDEX_STATS_OTHER_TYPES, row.otherTypes)
            .putLong(KEY_INDEX_STATS_TOTAL_BYTES, row.totalBytes)
            .putLong(KEY_INDEX_STATS_IMAGE_BYTES, row.imageBytes)
            .putLong(KEY_INDEX_STATS_VIDEO_BYTES, row.videoBytes)
            .putLong(KEY_INDEX_STATS_AUDIO_BYTES, row.audioBytes)
            .putLong(KEY_INDEX_STATS_DOCUMENT_BYTES, row.documentBytes)
            .putLong(KEY_INDEX_STATS_OTHER_TYPE_BYTES, row.otherTypeBytes)
            .apply()
    }

    companion object {
        private const val KEY_WIFI_ONLY = "wifi_only"
        private const val KEY_BACKGROUND_SYNC_ALWAYS = "background_sync_always"
        private const val KEY_AGGRESSIVE_THUMB_PREFETCH = "aggressive_thumbnail_prefetch"
        private const val KEY_SETUP_DONE = "setup_done"
        private const val KEY_SYNC_MODE = "sync_mode"
        private const val KEY_CLOUD_PROVIDER = "cloud_provider"
        private const val KEY_LAST_VERSION = "last_version_code"
        private const val KEY_AUTO_DELETE_REMOTE = "auto_delete_remote"
        private const val KEY_AUTO_FREE_LOCAL = "auto_free_local"
        private const val KEY_AUTO_FREE_IDLE = "auto_free_local_idle"
        private const val KEY_LOCAL_IDLE_RELEASE_DAYS = "local_idle_release_days"
        private const val KEY_AUTO_SYNC_PAUSED_UNTIL = "auto_sync_paused_until"

        const val MIN_LOCAL_IDLE_RELEASE_DAYS = 1
        const val MAX_LOCAL_IDLE_RELEASE_DAYS = 180
        const val DEFAULT_LOCAL_IDLE_RELEASE_DAYS = 90

        /** Tempo de respiro após cancelar antes de sync/index automáticos. */
        const val CANCEL_COOLDOWN_MS = 10 * 60 * 1000L

        /** Intervalo mínimo entre syncs bidirecionais automáticos (sem pendências). */
        const val AUTO_BIDIRECTIONAL_MIN_INTERVAL_MS = 12 * 60 * 1000L

        /** Re-tenta sync automático cedo só se ainda houver envios locais pendentes. */
        const val AUTO_BIDIRECTIONAL_UPLOAD_RETRY_MS = 3 * 60 * 1000L

        /** Não reagir ao MediaStore durante sync longo. */
        const val MEDIA_LIBRARY_SUPPRESS_DURING_SYNC_MS = 15 * 60 * 1000L

        /** Após liberar espaço local, o MediaStore dispara muitos eventos — ignorar por um tempo. */
        const val MEDIA_LIBRARY_SUPPRESS_AFTER_LOCAL_RELEASE_MS = 10 * 60 * 1000L

        private const val KEY_LAST_BIDIR_SYNC_DONE_MS = "last_bidir_sync_done_ms"
        private const val KEY_CACHED_PENDING_TRANSFER = "cached_pending_transfer_count"
        private const val KEY_MEDIA_WATCH_SUPPRESS_UNTIL_MS = "media_watch_suppress_until_ms"
        private const val KEY_GPS_BACKFILL_CURSOR = "gps_backfill_cursor"
        private const val KEY_THUMB_FAIL_COUNTS = "thumb_fail_counts"
        private const val KEY_THUMB_PREFETCH_CURSOR = "thumb_prefetch_cursor"
        private const val KEY_FORCE_REMOTE_FULL_LISTING = "force_remote_full_listing_once"
        private const val KEY_INDEX_STATS_CACHED = "index_stats_cached"
        private const val KEY_INDEX_STATS_TOTAL = "index_stats_total"
        private const val KEY_INDEX_STATS_WITH_LOCAL = "index_stats_with_local"
        private const val KEY_INDEX_STATS_SYNCED = "index_stats_synced"
        private const val KEY_INDEX_STATS_PENDING_UPLOAD = "index_stats_pending_upload"
        private const val KEY_INDEX_STATS_PENDING_DOWNLOAD = "index_stats_pending_download"
        private const val KEY_INDEX_STATS_CLOUD_ONLY = "index_stats_cloud_only"
        private const val KEY_INDEX_STATS_LOCAL_ONLY = "index_stats_local_only"
        private const val KEY_INDEX_STATS_ERRORS = "index_stats_errors"
        private const val KEY_INDEX_STATS_CONFLICTS = "index_stats_conflicts"
        private const val KEY_INDEX_STATS_ATTENTION = "index_stats_attention"
        private const val KEY_INDEX_STATS_IMAGES = "index_stats_images"
        private const val KEY_INDEX_STATS_VIDEOS = "index_stats_videos"
        private const val KEY_INDEX_STATS_AUDIO = "index_stats_audio"
        private const val KEY_INDEX_STATS_DOCUMENTS = "index_stats_documents"
        private const val KEY_INDEX_STATS_OTHER_TYPES = "index_stats_other_types"
        private const val KEY_INDEX_STATS_TOTAL_BYTES = "index_stats_total_bytes"
        private const val KEY_INDEX_STATS_IMAGE_BYTES = "index_stats_image_bytes"
        private const val KEY_INDEX_STATS_VIDEO_BYTES = "index_stats_video_bytes"
        private const val KEY_INDEX_STATS_AUDIO_BYTES = "index_stats_audio_bytes"
        private const val KEY_INDEX_STATS_DOCUMENT_BYTES = "index_stats_document_bytes"
        private const val KEY_INDEX_STATS_OTHER_TYPE_BYTES = "index_stats_other_type_bytes"
    }
}
