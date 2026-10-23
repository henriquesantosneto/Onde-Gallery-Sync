package com.onegallery.sync.data.local

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import com.onegallery.sync.TestApplication
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(application = TestApplication::class, sdk = [34])
class VideoThumbnailHelperTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    @Test
    fun isAcceptableThumbnailFile_rejectsEmptyFile() {
        val dir = tempDir.root
        val empty = File(dir, "empty-thumb.jpg")
        empty.writeBytes(byteArrayOf())
        assertFalse(VideoThumbnailHelper.isAcceptableThumbnailFile(empty))
    }

    @Test
    fun isGalleryVideoThumbnailFile_rejects96pxGiveUpStub() {
        val dir = tempDir.root
        val stub = File(dir, "give-up-stub.jpg")
        val bitmap = Bitmap.createBitmap(96, 96, Bitmap.Config.RGB_565)
        try {
            stub.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 80, out)
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue(VideoThumbnailHelper.isAcceptableThumbnailFile(stub))
        assertFalse(VideoThumbnailHelper.isGalleryVideoThumbnailFile(stub))
    }

    @Test
    fun isCloudPosterThumbnailFile_accepts128pxJpeg() {
        val dir = tempDir.root
        val poster = File(dir, "poster.jpg")
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.RGB_565)
        try {
            poster.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out)
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue(VideoThumbnailHelper.isCloudPosterThumbnailFile(poster))
        assertTrue(VideoThumbnailHelper.isGalleryVideoThumbnailFile(poster))
    }

    @Test
    fun isDisplayableVideoThumbnailFile_rejectsMostlyBlackFrame() {
        val dir = tempDir.root
        val black = File(dir, "black-frame.jpg")
        val bitmap = Bitmap.createBitmap(128, 128, Bitmap.Config.RGB_565)
        bitmap.eraseColor(0xFF000000.toInt())
        try {
            black.outputStream().use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, out)
            }
        } finally {
            bitmap.recycle()
        }
        assertTrue(VideoThumbnailHelper.isAcceptableThumbnailFile(black))
        assertFalse(VideoThumbnailHelper.isDisplayableVideoThumbnailFile(black))
        val decoded = BitmapFactory.decodeFile(black.absolutePath)
        assertTrue(decoded != null && decoded.width > 0)
        decoded?.recycle()
    }
}
