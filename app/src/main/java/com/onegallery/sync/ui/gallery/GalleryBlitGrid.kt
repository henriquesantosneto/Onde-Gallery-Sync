package com.onegallery.sync.ui.gallery

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.GestureDetector
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.widget.OverScroller
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt

/** Lado da miniatura decodificada. A rolagem só copia esse bitmap. */
internal const val BLIT_THUMB_PX = 192

private class DecodeJob(
    val key: String,
    val file: File?,
    val uri: Uri?,
)

/**
 * A grade inteira é uma view. O dedo só move o scroll e o [onDraw] copia
 * miniaturas que já estão na memória — o mesmo desenho da galeria Samsung.
 */
internal class GalleryBlitGridView(
    context: Context,
    private val mediaCache: MediaCacheRepository,
) : View(context) {
    var onOpenPath: (String) -> Unit = {}
    var onTogglePath: (String) -> Unit = {}
    var onLongPressPath: (String) -> Unit = {}
    var onDragPath: (String) -> Unit = {}
    var onPinch: (Float) -> Unit = {}
    var onMultiTouch: (Boolean) -> Unit = {}
    var onFirstIndex: (Int) -> Unit = {}
    var onEdges: (Boolean, Boolean) -> Unit = { _, _ -> }
    var onScrollEnd: () -> Unit = {}
    var onNearEnd: () -> Unit = {}

    var pageColor: Int = 0xFF000000.toInt()
    var placeholderColor: Int = 0xFF2A2A2A.toInt()
    var headerBackground: Int = 0xFF111111.toInt()
    var headerTextColor: Int = 0xFFFFFFFF.toInt()
    var selectionColor: Int = 0xFF0078D4.toInt()

    var cellMinPx: Int = 300
    var gapPx: Int = 8
    var padStartPx: Int = 16
    var padEndPx: Int = 16
    var padTopPx: Int = 8
    var padBottomPx: Int = 8
    var headerHeightPx: Int = 80
    var yearHeaderHeightPx: Int = 100
    var timeline: Boolean = true

    private var entries: List<GalleryGridEntry> = emptyList()
    private var slots: List<BlitSlot> = emptyList()
    private var contentHeight: Int = 0
    private var cellSize: Int = 1
    private var contentOffset: Int = 0
    private var firstVisible: Int = 0
    private var atStart: Boolean = true
    private var atEnd: Boolean = false
    private var selectionMode: Boolean = false
    private var selectedPaths: Set<String> = emptySet()
    private var scrollActive: Boolean = false
    private var flinging: Boolean = false
    private var selecting: Boolean = false
    private var lastDragPath: String? = null
    private var pinchDistance: Float = 0f

    private val scroller = OverScroller(context)
    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private val fillPaint = Paint()
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeFill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val badgeText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt()
        textAlign = Paint.Align.CENTER
    }
    private val dst = Rect()
    private val src = Rect()
    private val cacheLock = Any()
    private val memory = object : android.util.LruCache<String, Bitmap>(32 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
        override fun entryRemoved(evicted: Boolean, key: String, oldValue: Bitmap, newValue: Bitmap?) {
            if (evicted && newValue == null && !oldValue.isRecycled) oldValue.recycle()
        }
    }
    private val decodeThread = HandlerThread("gallery-blit").apply { start() }
    private val decodeHandler = Handler(decodeThread.looper)
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var decodeGeneration: Int = 0
    private val decodeRunnable = Runnable { enqueueVisible() }
    private val scrubEnd = Runnable { finishScroll() }

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean = true

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (selecting) return true
                val path = hit(e.x, e.y) ?: return true
                if (selectionMode) onTogglePath(path) else onOpenPath(path)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                val path = hit(e.x, e.y) ?: return
                selecting = true
                lastDragPath = path
                performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                onLongPressPath(path)
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (selecting) {
                    val path = hit(e2.x, e2.y) ?: return true
                    if (path != lastDragPath) {
                        lastDragPath = path
                        onDragPath(path)
                    }
                    return true
                }
                beginScroll()
                scrollBy(distanceY.roundToInt())
                return true
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
                if (selecting) return true
                beginScroll()
                flinging = true
                scroller.forceFinished(true)
                scroller.fling(
                    0,
                    contentOffset,
                    0,
                    (-velocityY * 1.15f).roundToInt(),
                    0,
                    0,
                    0,
                    maxScroll(),
                )
                postInvalidateOnAnimation()
                return true
            }
        },
    )

    fun bindEntries(next: List<GalleryGridEntry>) {
        if (next === entries && slots.isNotEmpty()) return
        val offset = contentOffset
        entries = next
        rebuild()
        contentOffset = offset.coerceIn(0, maxScroll())
        updateFirstIndex()
        invalidate()
        scheduleDecode()
    }

    fun bindMetrics(
        cellMin: Int,
        gap: Int,
        padStart: Int,
        padEnd: Int,
        padTop: Int,
        padBottom: Int,
        headerHeight: Int,
        yearHeaderHeight: Int,
        showTimeline: Boolean,
    ) {
        if (cellMin == cellMinPx && gap == gapPx && padStart == padStartPx && padEnd == padEndPx &&
            padTop == padTopPx && padBottom == padBottomPx && headerHeight == headerHeightPx &&
            yearHeaderHeight == yearHeaderHeightPx && showTimeline == timeline
        ) {
            return
        }
        val keep = firstVisible
        cellMinPx = cellMin
        gapPx = gap
        padStartPx = padStart
        padEndPx = padEnd
        padTopPx = padTop
        padBottomPx = padBottom
        headerHeightPx = headerHeight
        yearHeaderHeightPx = yearHeaderHeight
        timeline = showTimeline
        rebuild()
        if (slots.isNotEmpty()) {
            contentOffset = slots[keep.coerceIn(0, slots.lastIndex)].top.coerceIn(0, maxScroll())
        }
        updateFirstIndex()
        invalidate()
        scheduleDecode()
    }

    fun bindSelection(mode: Boolean, paths: Set<String>) {
        if (mode == selectionMode && paths == selectedPaths) return
        selectionMode = mode
        selectedPaths = paths
        invalidate()
    }

    fun scrollToEntry(index: Int) {
        scroller.forceFinished(true)
        flinging = false
        if (slots.isEmpty()) return
        val slot = slots[index.coerceIn(0, slots.lastIndex)]
        contentOffset = slot.top.coerceIn(0, maxScroll())
        updateFirstIndex()
        invalidate()
        beginScroll()
        mainHandler.removeCallbacks(scrubEnd)
        mainHandler.postDelayed(scrubEnd, 120)
    }

    fun release() {
        decodeGeneration++
        mainHandler.removeCallbacks(decodeRunnable)
        decodeHandler.removeCallbacksAndMessages(null)
        decodeThread.quitSafely()
        synchronized(cacheLock) { memory.evictAll() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val keep = firstVisible
        rebuild()
        if (slots.isNotEmpty()) {
            contentOffset = slots[keep.coerceIn(0, slots.lastIndex)].top.coerceIn(0, maxScroll())
        }
        updateFirstIndex()
        scheduleDecode()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            pinch(event)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            pinchDistance = 0f
            onMultiTouch(false)
        }
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            selecting = false
            lastDragPath = null
            pinchDistance = 0f
            if (scroller.isFinished) finishScroll()
        }
        return true
    }

    override fun computeScroll() {
        if (scroller.computeScrollOffset()) {
            contentOffset = scroller.currY.coerceIn(0, maxScroll())
            updateFirstIndex()
            postInvalidateOnAnimation()
        } else if (flinging) {
            flinging = false
            finishScroll()
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawColor(pageColor)
        if (slots.isEmpty()) return
        val bottom = contentOffset + height
        var index = blitFirstVisibleIndex(slots, contentOffset)
        canvas.save()
        canvas.translate(0f, -contentOffset.toFloat())
        synchronized(cacheLock) {
            while (index <= slots.lastIndex) {
                val slot = slots[index]
                if (slot.top >= bottom) break
                if (slot.path == null) {
                    drawHeader(canvas, slot)
                } else {
                    drawPhoto(canvas, slot)
                }
                index++
            }
        }
        canvas.restore()
    }

    private fun drawHeader(canvas: Canvas, slot: BlitSlot) {
        fillPaint.color = headerBackground
        canvas.drawRect(
            0f,
            slot.top.toFloat(),
            width.toFloat(),
            (slot.top + slot.height).toFloat(),
            fillPaint,
        )
        val title = slot.title ?: return
        textPaint.color = headerTextColor
        val textScale = resources.displayMetrics.density
        textPaint.textSize = if (slot.height >= yearHeaderHeightPx) 22f * textScale else 16f * textScale
        val metrics = textPaint.fontMetrics
        val baseline = slot.top + slot.height / 2f - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(title, padStartPx + 12f * resources.displayMetrics.density, baseline, textPaint)
    }

    private fun drawPhoto(canvas: Canvas, slot: BlitSlot) {
        val entry = entries.getOrNull(slot.entryIndex) as? GalleryGridEntry.Photo ?: return
        val item = entry.item
        dst.set(slot.left, slot.top, slot.left + slot.width, slot.top + slot.height)
        val bitmap = memory.get(gridThumbCacheKey(item, BLIT_THUMB_PX))
        if (bitmap == null || bitmap.isRecycled) {
            fillPaint.color = placeholderColor
            canvas.drawRect(dst, fillPaint)
        } else {
            centerCrop(bitmap, dst)
            canvas.drawBitmap(bitmap, src, dst, bitmapPaint)
        }
        if (selectionMode) {
            drawSelection(canvas, slot, item.canonicalPath)
        }
        drawBadge(canvas, slot, item)
        if (item.kind == MediaKind.VIDEO) {
            drawDuration(canvas, slot, item.videoDurationMs)
        }
    }

    private fun centerCrop(bitmap: Bitmap, destination: Rect) {
        val scale = max(
            destination.width().toFloat() / bitmap.width.coerceAtLeast(1),
            destination.height().toFloat() / bitmap.height.coerceAtLeast(1),
        )
        val srcW = (destination.width() / scale).roundToInt().coerceAtMost(bitmap.width)
        val srcH = (destination.height() / scale).roundToInt().coerceAtMost(bitmap.height)
        val left = ((bitmap.width - srcW) / 2).coerceAtLeast(0)
        val top = ((bitmap.height - srcH) / 2).coerceAtLeast(0)
        src.set(left, top, left + srcW, top + srcH)
    }

    private fun drawSelection(canvas: Canvas, slot: BlitSlot, path: String) {
        val selected = path in selectedPaths
        val radius = (slot.width * 0.07f).coerceIn(12f, 22f)
        val cx = slot.left + radius + 8f
        val cy = slot.top + radius + 8f
        badgeFill.style = Paint.Style.FILL
        badgeFill.color = if (selected) selectionColor else 0x66000000
        canvas.drawCircle(cx, cy, radius, badgeFill)
        badgeFill.style = Paint.Style.STROKE
        badgeFill.strokeWidth = 2f
        badgeFill.color = 0xFFFFFFFF.toInt()
        canvas.drawCircle(cx, cy, radius - 1f, badgeFill)
        badgeFill.style = Paint.Style.FILL
        if (selected) {
        badgeText.textAlign = Paint.Align.CENTER
        badgeText.color = 0xFFFFFFFF.toInt()
        badgeText.textSize = radius
        canvas.drawText("✓", cx, cy - (badgeText.ascent() + badgeText.descent()) / 2f, badgeText)
        }
    }

    private fun drawBadge(canvas: Canvas, slot: BlitSlot, item: GalleryDisplayItem) {
        val state = galleryGridCloudBadge(item) ?: return
        val radius = (slot.width * 0.065f).coerceIn(10f, 18f)
        val cx = slot.left + slot.width - radius - 8f
        val cy = slot.top + radius + 8f
        badgeFill.style = Paint.Style.FILL
        badgeFill.color = if (state == CloudSyncState.LOCAL_ONLY) 0xEEFFFFFF.toInt() else 0xFF0078D4.toInt()
        canvas.drawCircle(cx, cy, radius, badgeFill)
        badgeText.textAlign = Paint.Align.CENTER
        badgeText.color = if (state == CloudSyncState.LOCAL_ONLY) 0xFF202020.toInt() else 0xFFFFFFFF.toInt()
        badgeText.textSize = radius
        val mark = if (state == CloudSyncState.SYNCED) "✓" else ""
        if (mark.isNotEmpty()) {
            canvas.drawText(mark, cx, cy - (badgeText.ascent() + badgeText.descent()) / 2f, badgeText)
        }
    }

    private fun drawDuration(canvas: Canvas, slot: BlitSlot, durationMs: Long?) {
        val label = durationMs?.let { formatVideoDuration(it) }.orEmpty()
        val text = if (label.isEmpty()) "▶" else "▶ $label"
        badgeText.textAlign = Paint.Align.LEFT
        badgeText.color = 0xFFFFFFFF.toInt()
        badgeText.textSize = 11f * resources.displayMetrics.density
        val pad = 6f * resources.displayMetrics.density
        val textWidth = badgeText.measureText(text)
        val left = slot.left + pad
        val bottom = slot.top + slot.height - pad
        val top = bottom - badgeText.textSize - pad
        fillPaint.color = 0x99000000.toInt()
        canvas.drawRoundRect(left, top, left + textWidth + pad * 2, bottom, 8f, 8f, fillPaint)
        canvas.drawText(
            text,
            left + pad,
            bottom - pad / 2f - badgeText.descent(),
            badgeText,
        )
    }

    private fun rebuild() {
        if (width <= 0 || entries.isEmpty()) {
            slots = emptyList()
            contentHeight = 0
            return
        }
        val layout = buildBlitLayout(
            count = entries.size,
            isHeader = { entries[it] is GalleryGridEntry.Header },
            isYear = { (entries[it] as? GalleryGridEntry.Header)?.header?.kind == TimelineHeaderKind.YEAR },
            pathAt = { (entries[it] as? GalleryGridEntry.Photo)?.item?.canonicalPath },
            titleAt = { (entries[it] as? GalleryGridEntry.Header)?.header?.title },
            width = width,
            cellMin = cellMinPx.coerceAtLeast(1),
            gap = gapPx,
            padStart = padStartPx,
            padEnd = padEndPx,
            padTop = if (timeline) padTopPx else gapPx,
            padBottom = padBottomPx,
            headerHeight = headerHeightPx,
            yearHeaderHeight = yearHeaderHeightPx,
        )
        slots = layout.slots
        contentHeight = layout.contentHeight
        cellSize = layout.cellSize
    }

    private fun scrollBy(dy: Int) {
        if (dy == 0) return
        val next = (contentOffset + dy).coerceIn(0, maxScroll())
        if (next == contentOffset) return
        contentOffset = next
        updateFirstIndex()
        invalidate()
    }

    private fun maxScroll(): Int = (contentHeight - height).coerceAtLeast(0)

    private fun beginScroll() {
        if (scrollActive) return
        scrollActive = true
        decodeGeneration++
        decodeHandler.removeCallbacksAndMessages(null)
        GalleryGridScrollGate.isUserScrollingGrid = true
    }

    private fun finishScroll() {
        if (!scrollActive && !flinging) {
            scheduleDecode()
            maybeLoadMore()
            return
        }
        scrollActive = false
        flinging = false
        GalleryGridScrollGate.isUserScrollingGrid = false
        onScrollEnd()
        scheduleDecode()
        maybeLoadMore()
    }

    private fun updateFirstIndex() {
        val index = blitFirstVisibleIndex(slots, contentOffset)
        if (index != firstVisible) {
            firstVisible = index
            onFirstIndex(index)
        }
        val start = contentOffset <= 0
        val end = contentOffset >= maxScroll() - 1
        if (start != atStart || end != atEnd) {
            atStart = start
            atEnd = end
            onEdges(start, end)
        }
    }

    private fun maybeLoadMore() {
        if (scrollActive || slots.isEmpty()) return
        val last = blitFirstVisibleIndex(slots, contentOffset + height)
        if (last >= slots.lastIndex - GalleryGridPagination.loadMoreVisibleTailMargin) {
            onNearEnd()
        }
    }

    private fun hit(x: Float, y: Float): String? {
        return blitPathAt(slots, x.roundToInt(), (y + contentOffset).roundToInt())
    }

    private fun pinch(event: MotionEvent) {
        if (event.pointerCount < 2) return
        scroller.forceFinished(true)
        selecting = false
        val dx = event.getX(0) - event.getX(1)
        val dy = event.getY(0) - event.getY(1)
        val distance = kotlin.math.hypot(dx.toDouble(), dy.toDouble()).toFloat()
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                pinchDistance = distance
                onMultiTouch(true)
                finishScroll()
            }
            MotionEvent.ACTION_MOVE -> {
                val previous = pinchDistance
                if (previous > 1f && distance > 1f) {
                    val zoom = distance / previous
                    if (zoom.isFinite() && abs(zoom - 1f) > 0.001f) onPinch(zoom)
                }
                pinchDistance = distance
            }
            MotionEvent.ACTION_POINTER_UP, MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                pinchDistance = 0f
                onMultiTouch(false)
            }
        }
    }

    private fun scheduleDecode() {
        mainHandler.removeCallbacks(decodeRunnable)
        mainHandler.postDelayed(decodeRunnable, 32)
    }

    private fun enqueueVisible() {
        if (scrollActive || slots.isEmpty() || width == 0) return
        val generation = ++decodeGeneration
        val jobs = ArrayList<DecodeJob>()
        val first = blitFirstVisibleIndex(slots, contentOffset)
        var last = first
        val bottom = contentOffset + height
        while (last < slots.lastIndex && slots[last + 1].top < bottom) last++
        fun addRange(from: Int, to: Int) {
            if (from > to) return
            for (index in from..to) {
                val slot = slots.getOrNull(index) ?: continue
                val item = (entries.getOrNull(slot.entryIndex) as? GalleryGridEntry.Photo)?.item ?: continue
                val key = gridThumbCacheKey(item, BLIT_THUMB_PX)
                if (synchronized(cacheLock) { memory.get(key) } != null) continue
                val model = gridPaintModel(item, mediaCache) ?: continue
                val file = (model as? DiskThumb)?.file
                val uri = model as? Uri
                if (file == null && uri == null) continue
                jobs.add(DecodeJob(key, file, uri))
            }
        }
        addRange(first, last)
        addRange(last + 1, (last + 24).coerceAtMost(slots.lastIndex))
        addRange((first - 12).coerceAtLeast(0), first - 1)
        if (jobs.isEmpty()) return
        decodeHandler.post {
            for (job in jobs) {
                if (generation != decodeGeneration || GalleryGridScrollGate.isUserScrollingGrid) return@post
                val bitmap = decodeJob(job) ?: continue
                if (generation != decodeGeneration) {
                    bitmap.recycle()
                    return@post
                }
                mainHandler.post {
                    if (generation != decodeGeneration || bitmap.isRecycled) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    } else {
                        synchronized(cacheLock) { memory.put(job.key, bitmap) }
                        if (!scrollActive) invalidate()
                    }
                }
            }
        }
    }

    private fun decodeJob(job: DecodeJob): Bitmap? {
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            if (job.file != null) {
                BitmapFactory.decodeFile(job.file.absolutePath, bounds)
            } else {
                val uri = job.uri ?: return null
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, bounds)
                }
            }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, BLIT_THUMB_PX)
                inPreferredConfig = Bitmap.Config.RGB_565
            }
            val decoded = if (job.file != null) {
                BitmapFactory.decodeFile(job.file.absolutePath, options)
            } else {
                val uri = job.uri ?: return null
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
            } ?: return null
            if (!decoded.isMutable && decoded.config == Bitmap.Config.RGB_565) return decoded
            val immutable = decoded.copy(Bitmap.Config.RGB_565, false)
            if (immutable != null && immutable !== decoded) decoded.recycle()
            immutable ?: decoded
        }.getOrNull()
    }

    init {
        setWillNotDraw(false)
        isClickable = true
    }
}

private fun sampleSize(width: Int, height: Int, target: Int): Int {
    val longest = max(width, height).coerceAtLeast(1)
    var sample = 1
    while (longest / (sample * 2) >= target && sample < 32) sample *= 2
    return sample
}

@Composable
internal fun GalleryBlitPhotoGrid(
    entries: List<GalleryGridEntry>,
    dateTimeline: Boolean,
    gridScale: Float,
    cellMinPx: Int,
    selectionMode: Boolean,
    selectedPaths: Set<String>,
    mediaCache: MediaCacheRepository,
    scrollToTopToken: Int,
    onOpen: (String) -> Unit,
    onToggle: (String) -> Unit,
    onLongPress: (String) -> Unit,
    onDragSelect: (String) -> Unit,
    onPinch: (Float) -> Unit,
    onMultiTouch: (Boolean) -> Unit,
    onNearEnd: () -> Unit,
    onScrollEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val viewRef = remember { arrayOfNulls<GalleryBlitGridView>(1) }
    val bridge = remember { BlitScrollBridge() }
    DisposableEffect(Unit) {
        onDispose { viewRef[0]?.release() }
    }
    val background = MaterialTheme.colorScheme.background.toArgb()
    val placeholder = MaterialTheme.colorScheme.surfaceVariant.toArgb()
    val headerBackground = MaterialTheme.colorScheme.surfaceContainerLow.toArgb()
    val headerText = MaterialTheme.colorScheme.onSurface.toArgb()
    val selection = MaterialTheme.colorScheme.primary.toArgb()
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gapPx = with(density) { 4.dp.roundToPx() }
    val padStart = with(density) { 8.dp.roundToPx() }
    val padEnd = with(density) {
        (8.dp + if (dateTimeline) GalleryFastScrollRailReservedWidth else 0.dp).roundToPx()
    }
    val padTop = with(density) { GalleryStickyTimelineBarHeight.roundToPx() }
    val headerHeight = with(density) { 44.dp.roundToPx() }
    val yearHeader = with(density) { 56.dp.roundToPx() }
    Box(modifier) {
        AndroidView(
            modifier = Modifier.fillMaxSize(),
            factory = { context ->
                GalleryBlitGridView(context, mediaCache).also { view ->
                    viewRef[0] = view
                    bridge.view = view
                    view.onFirstIndex = { index -> bridge.firstIndex = index }
                    view.onEdges = { start, end ->
                        bridge.atStart = start
                        bridge.atEnd = end
                    }
                }
            },
            update = { view ->
                view.pageColor = background
                view.placeholderColor = placeholder
                view.headerBackground = headerBackground
                view.headerTextColor = headerText
                view.selectionColor = selection
                view.onOpenPath = onOpen
                view.onTogglePath = onToggle
                view.onLongPressPath = onLongPress
                view.onDragPath = onDragSelect
                view.onPinch = onPinch
                view.onMultiTouch = onMultiTouch
                view.onScrollEnd = onScrollEnd
                view.onNearEnd = onNearEnd
                view.bindMetrics(
                    cellMin = cellMinPx,
                    gap = gapPx,
                    padStart = padStart,
                    padEnd = padEnd,
                    padTop = padTop,
                    padBottom = gapPx,
                    headerHeight = headerHeight,
                    yearHeaderHeight = yearHeader,
                    showTimeline = dateTimeline,
                )
                view.bindSelection(selectionMode, selectedPaths)
                view.bindEntries(entries)
            },
        )
        if (dateTimeline) {
            BlitGridChrome(
                bridge = bridge,
                entries = entries,
                gridScale = gridScale,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
    if (!bridge.acceptedInitialToken) {
        bridge.handledScrollToTop = scrollToTopToken
        bridge.acceptedInitialToken = true
    }
    androidx.compose.runtime.LaunchedEffect(scrollToTopToken) {
        if (scrollToTopToken > bridge.handledScrollToTop) {
            bridge.handledScrollToTop = scrollToTopToken
            viewRef[0]?.scrollToEntry(0)
        }
    }
}

private class BlitScrollBridge {
    var firstIndex by mutableIntStateOf(0)
    var atStart by androidx.compose.runtime.mutableStateOf(true)
    var atEnd by androidx.compose.runtime.mutableStateOf(false)
    var handledScrollToTop: Int = -1
    var acceptedInitialToken: Boolean = false
    var view: GalleryBlitGridView? = null
}

@Composable
private fun BlitGridChrome(
    bridge: BlitScrollBridge,
    entries: List<GalleryGridEntry>,
    gridScale: Float,
    modifier: Modifier = Modifier,
) {
    val first = bridge.firstIndex
    val label = stickyLabelForGridIndex(entries, first, gridScale)
    val markers = remember(entries, gridScale) {
        buildGalleryFastScrollMarkers(entries, timelineGroupingForGridScale(gridScale))
    }
    val fraction = when {
        bridge.atEnd -> 1f
        bridge.atStart || entries.size <= 1 -> 0f
        else -> first.toFloat() / (entries.size - 1)
    }
    Box(modifier) {
        GalleryStickyTimelineBar(
            title = label,
            modifier = Modifier.align(androidx.compose.ui.Alignment.TopCenter),
        )
        if (entries.size >= 6 && markers.isNotEmpty()) {
            GridFastScrollRail(
                gridState = null,
                itemCount = entries.size,
                lastIndex = entries.lastIndex,
                markers = markers,
                labelForScrollIndex = { index ->
                    labelForFastScrollGridPosition(entries, index, gridScale)
                },
                modifier = Modifier.align(androidx.compose.ui.Alignment.CenterEnd),
                externalFraction = fraction,
                onScrollToIndex = { index -> bridge.view?.scrollToEntry(index) },
            )
        }
    }
}
