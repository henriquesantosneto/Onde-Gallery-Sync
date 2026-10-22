package com.onegallery.sync.ui.gallery

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class TimelineHeaderKind {
    YEAR,
    MONTH,
    DAY,
}

/** Granularidade das seções ao rolar / no rótulo fixo. */
enum class TimelineGrouping {
    /** Hoje, Ontem, mês, ano. */
    RELAXED,
    /** Uma seção por dia (melhor com miniaturas pequenas). */
    BY_DAY,
}

data class GalleryTimelineHeader(
    val id: String,
    val title: String,
    val kind: TimelineHeaderKind,
)

sealed class GalleryGridEntry(open val key: String) {
    data class Header(val header: GalleryTimelineHeader) : GalleryGridEntry(header.id)
    data class Photo(val item: GalleryDisplayItem) : GalleryGridEntry(item.canonicalPath)
}

private val ptBr = Locale("pt", "BR")
private val monthFormatter = DateTimeFormatter.ofPattern("MMMM", ptBr)

fun isDateTimelineSort(sortOrder: GallerySortOrder): Boolean =
    sortOrder == GallerySortOrder.DATE_NEWEST || sortOrder == GallerySortOrder.DATE_OLDEST

/**
 * Pinça para fora (escala baixa) → seções por mês/ano ([TimelineGrouping.RELAXED]).
 * Pinça para dentro → uma seção por dia.
 */
fun timelineGroupingForGridScale(gridScale: Float): TimelineGrouping =
    if (gridScale <= 0.72f) TimelineGrouping.RELAXED else TimelineGrouping.BY_DAY

private fun timestampToDate(epochSeconds: Long): LocalDate {
    if (epochSeconds <= 0L) return LocalDate.EPOCH
    return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
}

private fun capitalizeMonth(month: java.time.Month, year: Int): String {
    val raw = monthFormatter.format(LocalDate.of(year, month, 1))
    return raw.replaceFirstChar { if (it.isLowerCase()) it.titlecase(ptBr) else it.toString() }
}

fun formatMediaCaptureDate(epochSeconds: Long): String {
    if (epochSeconds <= 0L) return ""
    val date = timestampToDate(epochSeconds)
    return timelineSectionTitle(date, TimelineGrouping.RELAXED)
}

fun formatTimelineDayLabel(date: LocalDate, today: LocalDate = LocalDate.now()): String {
    return when {
        date == today -> "Hoje"
        date == today.minusDays(1) -> "Ontem"
        date.year == today.year -> "${date.dayOfMonth} de ${capitalizeMonth(date.month, date.year)}"
        else -> "${date.dayOfMonth} de ${capitalizeMonth(date.month, date.year)} de ${date.year}"
    }
}

/** Mesma chave de seção que [buildGalleryGridEntries] (ex.: "day-today" → Hoje). */
fun timelineSectionKey(
    epochSeconds: Long,
    grouping: TimelineGrouping,
    today: LocalDate = LocalDate.now(),
): String {
    val date = timestampToDate(epochSeconds)
    return when (grouping) {
        TimelineGrouping.BY_DAY -> "day-$date"
        TimelineGrouping.RELAXED -> when {
            date == today -> "day-today"
            date == today.minusDays(1) -> "day-yesterday"
            else -> "month-${date.year}-${date.monthValue}"
        }
    }
}

fun timelineSectionKeyForItem(
    item: GalleryDisplayItem,
    grouping: TimelineGrouping,
    today: LocalDate = LocalDate.now(),
): String = timelineSectionKey(item.sortTimestamp, grouping, today)

fun timelineSectionTitle(date: LocalDate, grouping: TimelineGrouping, today: LocalDate = LocalDate.now()): String {
    return when (grouping) {
        TimelineGrouping.BY_DAY -> formatTimelineDayLabel(date, today)
        TimelineGrouping.RELAXED -> when {
            date == today -> "Hoje"
            date == today.minusDays(1) -> "Ontem"
            date.year == today.year -> capitalizeMonth(date.month, date.year)
            else -> "${capitalizeMonth(date.month, date.year)} ${date.year}"
        }
    }
}

fun timelineStickyLabel(epochSeconds: Long, gridScale: Float): String {
    val date = timestampToDate(epochSeconds)
    return timelineSectionTitle(date, timelineGroupingForGridScale(gridScale))
}

fun sortedTimelinePhotos(
    items: List<GalleryDisplayItem>,
    sortOrder: GallerySortOrder,
): List<GalleryDisplayItem> {
    if (!isDateTimelineSort(sortOrder)) return items
    return if (sortOrder == GallerySortOrder.DATE_NEWEST) {
        items.sortedByDescending { it.sortTimestamp }
    } else {
        items.sortedBy { it.sortTimestamp }
    }
}

fun buildGalleryGridEntries(
    items: List<GalleryDisplayItem>,
    sortOrder: GallerySortOrder,
    grouping: TimelineGrouping = TimelineGrouping.RELAXED,
    /** Verdadeiro quando [items] já veio de [GalleryCatalogSession.sortedLibraryItems] na ordem de data. */
    itemsAlreadySortedForTimeline: Boolean = false,
): List<GalleryGridEntry> {
    if (!isDateTimelineSort(sortOrder)) {
        return items.map { GalleryGridEntry.Photo(it) }
    }
    val sorted = if (itemsAlreadySortedForTimeline) {
        items
    } else {
        sortedTimelinePhotos(items, sortOrder)
    }
    val today = LocalDate.now()
    val entries = mutableListOf<GalleryGridEntry>()
    var lastYear: Int? = null
    var lastSectionKey: String? = null

    for (item in sorted) {
        val date = timestampToDate(item.sortTimestamp)
        val year = date.year

        if (grouping == TimelineGrouping.RELAXED && year != lastYear) {
            if (year != today.year) {
                entries.add(
                    GalleryGridEntry.Header(
                        GalleryTimelineHeader(
                            id = "year-$year",
                            title = year.toString(),
                            kind = TimelineHeaderKind.YEAR,
                        ),
                    ),
                )
            }
            lastYear = year
            lastSectionKey = null
        }

        val sectionKey = timelineSectionKey(item.sortTimestamp, grouping, today)
        val sectionTitle = timelineSectionTitle(date, grouping, today)
        val headerKind = if (grouping == TimelineGrouping.BY_DAY) {
            TimelineHeaderKind.DAY
        } else {
            TimelineHeaderKind.MONTH
        }

        if (sectionKey != lastSectionKey) {
            entries.add(
                GalleryGridEntry.Header(
                    GalleryTimelineHeader(
                        id = sectionKey,
                        title = sectionTitle,
                        kind = headerKind,
                    ),
                ),
            )
            lastSectionKey = sectionKey
        }
        entries.add(GalleryGridEntry.Photo(item))
    }
    return entries
}
