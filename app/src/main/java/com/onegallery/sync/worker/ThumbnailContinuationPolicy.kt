package com.onegallery.sync.worker

/**
 * Miniaturas pendentes continuam em levas curtas, fora do cooldown do sync completo.
 * Dois nomes únicos alternados: o worker que está rodando agenda o outro, sem cadeia infinita.
 */
object ThumbnailContinuationPolicy {
    const val PRIMARY_UNIQUE = "onegallery_thumbnail_continuation"
    const val FOLLOW_UP_UNIQUE = "onegallery_thumbnail_continuation_followup"
    const val SLOT_PRIMARY = "primary"
    const val SLOT_FOLLOW_UP = "followup"

    const val NORMAL_DELAY_SECONDS = 20L
    const val BUSY_DELAY_SECONDS = 90L
    const val STALL_DELAY_SECONDS = 180L

    /** Uma leva por execução do WorkManager (não o laço longo dentro do processo). */
    const val CHUNK_SIZE = 512

    fun followUpUniqueName(runningSlot: String?): String = when (runningSlot) {
        SLOT_PRIMARY -> FOLLOW_UP_UNIQUE
        SLOT_FOLLOW_UP -> PRIMARY_UNIQUE
        else -> PRIMARY_UNIQUE
    }

    fun slotForUniqueName(uniqueName: String): String = when (uniqueName) {
        FOLLOW_UP_UNIQUE -> SLOT_FOLLOW_UP
        else -> SLOT_PRIMARY
    }

    fun shouldScheduleAnother(
        moreRemain: Boolean,
        setupComplete: Boolean,
        backgroundEnabled: Boolean,
        paused: Boolean,
        userCancelled: Boolean,
    ): Boolean = moreRemain && setupComplete && backgroundEnabled && !paused && !userCancelled

    fun delaySeconds(stalled: Boolean, busy: Boolean): Long = when {
        stalled -> STALL_DELAY_SECONDS
        busy -> BUSY_DELAY_SECONDS
        else -> NORMAL_DELAY_SECONDS
    }
}

data class ScheduledThumbnailContinuation(
    val moreRemain: Boolean,
    val stalled: Boolean,
    val busy: Boolean,
)
