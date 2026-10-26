package com.onegallery.sync.ui.gallery

import android.graphics.Bitmap
import coil.size.Precision
import com.onegallery.sync.TestApplication
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = TestApplication::class)
class GalleryGridGpuRequestTest {

    @Test
    fun gridThumbIsASmallSoftwareBitmapReadyToBlit() {
        val request = galleryGridImageRequest(
            context = RuntimeEnvironment.getApplication(),
            data = DiskThumb(File("camera/a.jpg")),
            sizePx = 240,
            cacheKey = "camera/a.jpg:grid",
        )
        assertFalse(request.allowHardware)
        assertTrue(request.allowRgb565)
        assertEquals(Bitmap.Config.RGB_565, request.bitmapConfig)
        assertEquals(Precision.INEXACT, request.precision)
    }
}
