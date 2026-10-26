package com.onegallery.sync.ui.gallery

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import com.onegallery.sync.TestApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class GalleryBlitBadgeIconTest {

    @Test
    fun outlinedBadgesKeepTheOpenPhotoGeometry() {
        val cloud = Icons.Outlined.Cloud
        val synced = Icons.Outlined.CloudDone
        val phone = Icons.Outlined.Smartphone
        assertTrue(pathBounds(cloud).width() > 8f)
        assertTrue(pathBounds(synced).height() > 8f)
        assertTrue(pathBounds(phone).height() > 8f)
        assertTrue(nodeCount(cloud) != nodeCount(synced))
        listOf(cloud, synced, phone).forEach { icon ->
            val bitmap = rasterizeBadgeIcon(icon, 0xFF0078D4.toInt())
            assertEquals(128, bitmap.width)
            assertEquals(128, bitmap.height)
            assertEquals(Bitmap.Config.ARGB_8888, bitmap.config)
            assertFalse(bitmap.isMutable)
            assertFalse(bitmap.isRecycled)
        }
    }

    private fun pathBounds(icon: ImageVector): RectF {
        val bounds = RectF()
        fun walk(group: VectorGroup) {
            for (index in 0 until group.size) {
                when (val node = group[index]) {
                    is VectorGroup -> walk(node)
                    is VectorPath -> node.pathData.toPath().asAndroidPath().computeBounds(bounds, true)
                }
            }
        }
        walk(icon.root)
        return bounds
    }

    private fun nodeCount(icon: ImageVector): Int {
        var count = 0
        fun walk(group: VectorGroup) {
            for (index in 0 until group.size) {
                when (val node = group[index]) {
                    is VectorGroup -> walk(node)
                    is VectorPath -> count += node.pathData.size
                }
            }
        }
        walk(icon.root)
        return count
    }
}
