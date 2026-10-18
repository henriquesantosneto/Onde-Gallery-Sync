package com.onegallery.sync.ui.gallery

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class TimelineHeaderKind {
    YEAR,
    MONTH,
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
    val today = LocalDate.now()
    return when {
        date == today -> "Hoje"
        date == today.minusDays(1) -> "Ontem"
        date.year == today.year -> capitalizeMonth(date.month, date.year)
        else -> "${capitalizeMonth(date.month, date.year)} ${date.year}"
    }
}

fun buildGalleryGridEntries(
    items: List<GalleryDisplayItem>,
    sortOrder: GallerySortOrder,
): List<GalleryGridEntry> {
    if (sortOrder != GallerySortOrder.DATE_NEWEST && sortOrder != GallerySortOrder.DATE_OLDEST) {
        return items.map { GalleryGridEntry.Photo(it) }
    }
    val sorted = if (sortOrder == GallerySortOrder.DATE_NEWEST) {
        items.sortedByDescending { it.sortTimestamp }
    } else {
        items.sortedBy { it.sortTimestamp }
    }
    val today = LocalDate.now()
    val entries = mutableListOf<GalleryGridEntry>()
    var lastYear: Int? = null
    var lastSectionKey: String? = null

    for (item in sorted) {
        val date = timestampToDate(item.sortTimestamp)
        val year = date.year

        if (year != lastYear) {
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

        val sectionKey = when {
            date == today -> "day-today"
            date == today.minusDays(1) -> "day-yesterday"
            date.year == today.year -> "month-${date.year}-${date.monthValue}"
            else -> "month-${date.year}-${date.monthValue}"
        }
        val sectionTitle = when {
            date == today -> "Hoje"
            date == today.minusDays(1) -> "Ontem"
            date.year == today.year -> capitalizeMonth(date.month, year)
            else -> "${capitalizeMonth(date.month, year)} $year"
        }

        if (sectionKey != lastSectionKey) {
            entries.add(
                GalleryGridEntry.Header(
                    GalleryTimelineHeader(
                        id = sectionKey,
                        title = sectionTitle,
                        kind = TimelineHeaderKind.MONTH,
                    ),
                ),
            )
            lastSectionKey = sectionKey
        }
        entries.add(GalleryGridEntry.Photo(item))
    }
    return entries
}
