package com.onegallery.sync.ui.places

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import coil.ImageLoader
import coil.imageLoader
import coil.request.ImageRequest
import coil.size.Size
import com.onegallery.sync.sync.GalleryThumbnailCache
import com.onegallery.sync.ui.gallery.GalleryDisplayItem
import coil.request.SuccessResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Miniaturas empilhadas no estilo mapa da Apple Photos. */
object PhotoStackMarkerBitmap {
    private const val MAX_VISIBLE = 3

    suspend fun create(
        context: Context,
        items: List<GalleryDisplayItem>,
        thumbnailCache: GalleryThumbnailCache,
    ): Bitmap = withContext(Dispatchers.Default) {
        if (items.size == 1) {
            val path = items.first().canonicalPath
            MapMarkerBitmapCache.get(path)?.let { return@withContext it }
        }
        val density = context.resources.displayMetrics.density
        val cardW = 52f * density
        val cardH = 52f * density
        val pad = 10f * density
        val canvasSize = (cardW + pad * 2 + 12f * density).toInt()
        val output = Bitmap.createBitmap(canvasSize, canvasSize, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(output)
        val cx = canvasSize / 2f
        val cy = canvasSize / 2f + 4f * density

        val stackItems = items.take(MAX_VISIBLE)
        val photos = stackItems.map { item -> loadThumbBitmap(context, item, thumbnailCache) }

        val layouts = stackLayout(photos.size, density)
        photos.forEachIndexed { index, bitmap ->
            val layout = layouts[index]
            drawPhotoCard(
                canvas = canvas,
                photo = bitmap,
                centerX = cx + layout.offsetX,
                centerY = cy + layout.offsetY,
                rotationDeg = layout.rotation,
                width = cardW,
                height = cardH,
                cornerRadius = 10f * density,
            )
        }

        if (items.size > MAX_VISIBLE) {
            drawCountBadge(
                canvas = canvas,
                count = items.size,
                right = canvasSize - 4f * density,
                top = 4f * density,
                density = density,
            )
        }
        if (items.size == 1) {
            MapMarkerBitmapCache.put(items.first().canonicalPath, output)
        }
        output
    }

    private data class StackLayout(val offsetX: Float, val offsetY: Float, val rotation: Float)

    private fun stackLayout(count: Int, density: Float): List<StackLayout> {
        return when (count) {
            1 -> listOf(StackLayout(0f, 0f, 0f))
            2 -> listOf(
                StackLayout(-7f * density, 5f * density, -12f),
                StackLayout(7f * density, -4f * density, 8f),
            )
            else -> listOf(
                StackLayout(-10f * density, 6f * density, -14f),
                StackLayout(0f, 2f * density, 0f),
                StackLayout(10f * density, -5f * density, 12f),
            )
        }.take(count)
    }

    private suspend fun loadThumbBitmap(
        context: Context,
        item: GalleryDisplayItem,
        thumbnailCache: GalleryThumbnailCache,
    ): Bitmap? {
        item.thumbnailModel?.let { model ->
            decodeModel(context, model)?.let { return it }
        }
        val cached = thumbnailCache.ensureThumbnailCached(item.canonicalPath).getOrNull()
        if (cached != null) {
            decodeModel(context, cached)?.let { return it }
        }
        return null
    }

    private suspend fun decodeModel(context: Context, model: Any): Bitmap? {
        return when (model) {
            is Bitmap -> model
            is java.io.File -> ExifOrientedBitmap.decodeFile(model.absolutePath, maxEdgePx = 128)
            else -> {
                val loader: ImageLoader = context.imageLoader
                val request = ImageRequest.Builder(context)
                    .data(model)
                    .size(Size(128, 128))
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                if (result !is SuccessResult) return null
                val drawable = result.drawable
                val w = drawable.intrinsicWidth.coerceAtLeast(1)
                val h = drawable.intrinsicHeight.coerceAtLeast(1)
                Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { bitmap ->
                    Canvas(bitmap).apply { drawable.setBounds(0, 0, w, h); drawable.draw(this) }
                }
            }
        }
    }

    private fun drawPhotoCard(
        canvas: Canvas,
        photo: Bitmap?,
        centerX: Float,
        centerY: Float,
        rotationDeg: Float,
        width: Float,
        height: Float,
        cornerRadius: Float,
    ) {
        val left = -width / 2f
        val top = -height / 2f
        val rect = RectF(left, top, left + width, top + height)

        canvas.save()
        canvas.translate(centerX, centerY)
        canvas.rotate(rotationDeg)
        canvas.drawShadow(rect, cornerRadius)
        val clipPath = Path().apply { addRoundRect(rect, cornerRadius, cornerRadius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clipPath)
        if (photo != null) {
            canvas.drawBitmap(
                photo,
                null,
                rect,
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG),
            )
        } else {
            canvas.drawColor(Color.parseColor("#FFE0E0E0"))
        }
        canvas.restore()
        val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            color = Color.WHITE
            strokeWidth = 3f
        }
        canvas.drawRoundRect(rect, cornerRadius, cornerRadius, border)
        canvas.restore()
    }

    private fun Canvas.drawShadow(rect: RectF, cornerRadius: Float) {
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(70, 0, 0, 0)
            style = Paint.Style.FILL
        }
        save()
        translate(2f, 3f)
        drawRoundRect(rect, cornerRadius, cornerRadius, shadow)
        restore()
    }

    private fun drawCountBadge(
        canvas: Canvas,
        count: Int,
        right: Float,
        top: Float,
        density: Float,
    ) {
        val text = if (count > 99) "99+" else count.toString()
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.parseColor("#E6000000")
            textSize = 11f * density
            typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        }
        val textW = paint.measureText(text)
        val padH = 5f * density
        val padV = 3f * density
        val badgeW = textW + padH * 2
        val badgeH = 11f * density + padV * 2
        val left = right - badgeW
        val rect = RectF(left, top, right, top + badgeH)
        val bg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE }
        canvas.drawRoundRect(rect, badgeH / 2f, badgeH / 2f, bg)
        canvas.drawText(
            text,
            left + padH,
            top + badgeH - padV - 2f,
            Paint(paint).apply { color = Color.BLACK },
        )
    }
}
