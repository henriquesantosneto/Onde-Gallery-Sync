package com.onegallery.sync.ui.gallery

import com.onegallery.sync.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class IndexStatsSqlTest {
    @Test
    fun usageStatsQuery_readsTheIndexOnce() {
        val sql = GalleryMapper.buildSyncIndexStatsSql()
        assertEquals(1, Regex("FROM sync_metadata", RegexOption.IGNORE_CASE).findAll(sql).count())
        assertEquals(false, sql.contains("SELECT COUNT"))
    }
}
