package com.onegallery.sync.ui.gallery

import com.onegallery.sync.data.local.MediaAlbum
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Marcadores para rolagem rápida na grade de álbuns / locais. */
fun buildAlbumGridFastScrollMarkers(
    albums: List<MediaAlbum>,
    sort: AlbumListSort,
): List<FastScrollMarker> {
    if (albums.isEmpty()) return emptyList()
    val markers = mutableListOf<FastScrollMarker>()
    var lastYear: Int? = null
    var lastMonthKey: String? = null
    var lastLetter: Char? = null

    albums.forEachIndexed { index, album ->
        when (sort) {
            AlbumListSort.DATE_NEWEST -> {
                val date = timestampToLocalDate(album.sortTimestamp)
                val year = date.year
                if (year != lastYear) {
                    markers.add(
                        FastScrollMarker(
                            itemIndex = index,
                            label = year.toString(),
                            kind = FastScrollMarkerKind.YEAR,
                        ),
                    )
                    lastYear = year
                    lastMonthKey = null
                }
                val monthKey = "${date.year}-${date.monthValue}"
                if (monthKey != lastMonthKey) {
                    val monthLabel = timelineSectionTitle(date, TimelineGrouping.RELAXED)
                    markers.add(
                        FastScrollMarker(
                            itemIndex = index,
                            label = monthLabel,
                            kind = FastScrollMarkerKind.MONTH,
                        ),
                    )
                    lastMonthKey = monthKey
                }
            }
            AlbumListSort.NAME_A_Z,
            AlbumListSort.NAME_Z_A,
            -> {
                val letter = album.title.firstOrNull()?.uppercaseChar() ?: '#'
                if (letter != lastLetter) {
                    markers.add(
                        FastScrollMarker(
                            itemIndex = index,
                            label = letter.toString(),
                            kind = FastScrollMarkerKind.SECTION,
                        ),
                    )
                    lastLetter = letter
                }
            }
        }
    }
    return markers
}

private fun timestampToLocalDate(epochSeconds: Long): LocalDate {
    if (epochSeconds <= 0L) return LocalDate.EPOCH
    return Instant.ofEpochSecond(epochSeconds).atZone(ZoneId.systemDefault()).toLocalDate()
}
