package com.onegallery.sync.ui.gallery

import android.net.Uri
import com.onegallery.sync.TestApplication
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.sync.PendingDeleteTarget
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class GalleryTimelineKeysTest {

    @Test
    fun interleavedDay_doesNotReuseLazyKeyAndKeepsOneHeader() {
        val day = LocalDate.of(2026, 10, 22)
        val other = LocalDate.of(2026, 10, 21)
        val items = listOf(
            photo("a.jpg", epoch(day)),
            photo("b.jpg", epoch(other)),
            photo("c.jpg", epoch(day)),
        )

        val entries = buildGalleryGridEntries(
            items = items,
            sortOrder = GallerySortOrder.DATE_NEWEST,
            grouping = TimelineGrouping.BY_DAY,
            itemsAlreadySortedForTimeline = true,
        )

        val keys = entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        val dayHeaders = entries.filterIsInstance<GalleryGridEntry.Header>()
            .filter { it.header.id == "day-$day" }
        assertEquals(1, dayHeaders.size)
        val photos = entries.filterIsInstance<GalleryGridEntry.Photo>().map { it.item.canonicalPath }
        assertEquals(listOf("a.jpg", "c.jpg", "b.jpg"), photos)
    }

    @Test
    fun duplicatePath_getsDistinctLazyKey() {
        val day = LocalDate.of(2026, 10, 22)
        val items = listOf(
            photo("same.jpg", epoch(day)),
            photo("same.jpg", epoch(day) + 10),
        )
        val entries = buildGalleryGridEntries(
            items = items,
            sortOrder = GallerySortOrder.DATE_NEWEST,
            grouping = TimelineGrouping.BY_DAY,
            itemsAlreadySortedForTimeline = true,
        )
        val keys = entries.map { it.key }
        assertEquals(keys.size, keys.toSet().size)
        assertTrue(keys.contains("same.jpg"))
        assertTrue(keys.any { it.startsWith("same.jpg#") })
    }

    @Test
    fun smallLibrary_publishSortsBothDateOrders() {
        val day = LocalDate.of(2026, 10, 22)
        val newer = LocalDate.of(2026, 10, 23)
        val items = listOf(
            photo("old.jpg", epoch(day)),
            photo("new.jpg", epoch(newer)),
        )
        val session = GalleryCatalogSession()
        session.publish(
            GalleryCatalogSession.SnapshotBundle(
                catalogItems = items,
                visibleItems = items,
                userAlbums = emptyList(),
                fingerprint = GalleryCatalogFingerprint.fromDisplayPaths(items.map { it.canonicalPath }),
                localRevision = 1L,
            ),
            GallerySortOrder.DATE_NEWEST,
        )

        assertEquals(
            listOf("new.jpg", "old.jpg"),
            session.sortedLibraryItems(items, GallerySortOrder.DATE_NEWEST).map { it.canonicalPath },
        )
        assertEquals(
            listOf("old.jpg", "new.jpg"),
            session.sortedLibraryItems(items, GallerySortOrder.DATE_OLDEST).map { it.canonicalPath },
        )
    }

    @Test
    fun largeLibraryDoesNotReuseThePreviousDateOrder() {
        val session = GalleryCatalogSession()
        val first = listOf(
            photo("a.jpg", 10L),
            photo("b.jpg", 20L),
        )
        session.publish(bundleOf(first), GallerySortOrder.DATE_OLDEST)
        assertEquals(
            listOf("a.jpg", "b.jpg"),
            session.sortedLibraryItems(first, GallerySortOrder.DATE_OLDEST).map { it.canonicalPath },
        )
        val grown = (1..400).map { index -> photo("p$index.jpg", index.toLong()) }
        session.publish(bundleOf(grown), GallerySortOrder.DATE_NEWEST)
        assertEquals(
            "p400.jpg",
            session.sortedLibraryItems(grown, GallerySortOrder.DATE_NEWEST).first().canonicalPath,
        )
        assertEquals(
            "p1.jpg",
            session.sortedLibraryItems(grown, GallerySortOrder.DATE_OLDEST).first().canonicalPath,
        )
        assertEquals(
            listOf("p1.jpg", "p10.jpg"),
            session.sortedLibraryItems(grown, GallerySortOrder.NAME_A_Z)
                .map { it.canonicalPath }
                .take(2),
        )
    }

    private fun bundleOf(items: List<GalleryDisplayItem>) = GalleryCatalogSession.SnapshotBundle(
        catalogItems = items,
        visibleItems = items,
        userAlbums = emptyList(),
        fingerprint = GalleryCatalogFingerprint.fromDisplayPaths(items.map { it.canonicalPath }),
        localRevision = 1L,
    )

    private fun epoch(date: LocalDate): Long =
        date.atStartOfDay(ZoneId.systemDefault()).toEpochSecond()

    private fun photo(path: String, timestamp: Long) = GalleryDisplayItem(
        canonicalPath = path,
        displayName = path,
        albumName = "Camera",
        kind = MediaKind.IMAGE,
        cloudState = CloudSyncState.SYNCED,
        localMediaId = 1L,
        oneDriveItemId = null,
        pendingDeleteTarget = PendingDeleteTarget.NONE,
        localUri = Uri.parse("content://media/external/images/1"),
        cachedOriginal = null,
        sortTimestamp = timestamp,
    )
}
