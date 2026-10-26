package com.onegallery.sync.ui.albums

import com.onegallery.sync.data.local.MediaAlbum
import org.junit.Assert.assertEquals
import org.junit.Test

class AlbumsGridEntriesTest {

    @Test
    fun duplicateBucketIdsGetDistinctLazyKeys() {
        val albums = listOf(
            MediaAlbum(bucketId = "album:Camera", title = "Camera", itemCount = 1),
            MediaAlbum(bucketId = "album:Camera", title = "Camera", itemCount = 2),
        )
        val keys = albumGridEntryKeys(buildAlbumGridEntries(albums))
        assertEquals(
            listOf("album-entry-album:Camera", "album-entry-album:Camera#2"),
            keys,
        )
        assertEquals(keys.size, keys.toSet().size)
    }
}
