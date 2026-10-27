package com.onegallery.sync.ui.gallery

import android.app.ActivityManager
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
import android.view.ViewConfiguration
import android.widget.OverScroller
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.CloudDone
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.onegallery.sync.data.local.MediaCacheRepository
import com.onegallery.sync.data.local.MediaKind
import com.onegallery.sync.sync.CloudSyncState
import com.onegallery.sync.ui.theme.OneDriveBlue
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** A célula pede pelo menos isto, e no máximo isto, para não ampliar um JPEG minúsculo. */
internal fun blitThumbTargetPx(cellPx: Int): Int = cellPx.coerceIn(360, 640)

/**
 * A pinça não pode deixar a seleção que este mesmo gesto acabou de abrir.
 * Seleção que já existia antes do dedo descer continua.
 */
internal fun pinchAbortsSelectionOpenedByThisGesture(
    pointerCount: Int,
    openedSelectionThisGesture: Boolean,
): Boolean = pointerCount >= 2 && openedSelectionThisGesture

/** Um terço do large heap, entre 128 e 256 MB: as miniaturas desta visita ficam residentes. */
internal fun blitSessionCacheBytes(largeHeapMb: Int): Int {
    val mb = (largeHeapMb / 3).coerceIn(128, 256)
    return mb * 1024 * 1024
}

/**
 * Quem está mais longe da tela sai primeiro.
 * O intervalo [protectFrom, protectTo] é a janela visível e a vizinhança, e não entra na lista.
 */
internal fun blitThumbEvictOrder(
    indexes: Map<String, Int>,
    anchor: Int,
    protectFrom: Int,
    protectTo: Int,
): List<String> {
    return indexes.entries
        .filter { it.value !in protectFrom..protectTo }
        .sortedByDescending { abs(it.value - anchor) }
        .map { it.key }
}

/** Já está perto do tamanho da célula: não decodifica de novo. */
internal fun blitThumbCoversTarget(bitmapEdge: Int, targetPx: Int): Boolean =
    bitmapEdge >= targetPx - 32

private const val BLIT_SESSION_PROTECT_SLOTS = 6
private const val BLIT_PREFETCH_WAVE = 48

/**
 * Ordem de pré-carga: o que está na tela, depois o mais perto, dos dois lados.
 * [startStep] continua de onde a leva anterior parou.
 */
internal fun blitPrefetchScan(
    slotCount: Int,
    anchor: Int,
    visibleFrom: Int,
    visibleTo: Int,
    startStep: Int,
    maxIndexes: Int,
): BlitPrefetchScan {
    if (slotCount <= 0 || maxIndexes <= 0) {
        return BlitPrefetchScan(emptyList(), startStep.coerceAtLeast(0), false)
    }
    val from = visibleFrom.coerceIn(0, slotCount - 1)
    val to = visibleTo.coerceIn(from, slotCount - 1)
    val safeAnchor = anchor.coerceIn(0, slotCount - 1)
    val indexes = ArrayList<Int>(maxIndexes)
    var seen = 0
    val skip = startStep.coerceAtLeast(0)
    fun offer(index: Int): Boolean {
        if (seen >= skip) indexes.add(index)
        seen++
        return indexes.size >= maxIndexes
    }
    for (index in from..to) {
        if (offer(index)) return BlitPrefetchScan(indexes, seen, true)
    }
    var distance = 0
    while (distance < slotCount) {
        val left = safeAnchor - distance
        val right = safeAnchor + distance
        if (left >= 0 && left !in from..to && offer(left)) {
            return BlitPrefetchScan(indexes, seen, true)
        }
        if (right != left && right < slotCount && right !in from..to && offer(right)) {
            return BlitPrefetchScan(indexes, seen, true)
        }
        distance++
    }
    return BlitPrefetchScan(indexes, seen, false)
}

internal data class BlitPrefetchScan(
    val indexes: List<Int>,
    val nextStep: Int,
    val more: Boolean,
)

private class DecodeJob(
    val path: String,
    val file: File?,
    val uri: Uri?,
    val fallbackFile: File? = null,
)

/**
 * O dedo que chega no meio do embalo trava o offset atual.
 * [onFingerDown] devolve true só quando o OverScroller precisa de forceFinished —
 * abortAnimation pularia para o fim do fling.
 */
internal class BlitCoast {
    var fingerDown: Boolean = false
        private set
    var flinging: Boolean = false
        private set
    var ignoreTapAfterCoast: Boolean = false
        private set
    private var lastHaltTime: Long = Long.MIN_VALUE

    fun onFlingStarted() {
        flinging = true
    }

    fun onFlingSettled() {
        flinging = false
    }

    fun onFingerDown(eventTimeMs: Long, scrollerActive: Boolean): Boolean {
        fingerDown = true
        val moving = flinging || scrollerActive
        if (moving) {
            flinging = false
            ignoreTapAfterCoast = true
            lastHaltTime = eventTimeMs
            return true
        }
        val justHalted = ignoreTapAfterCoast &&
            eventTimeMs != lastHaltTime &&
            eventTimeMs - lastHaltTime in 0..48
        if (eventTimeMs != lastHaltTime && !justHalted) {
            ignoreTapAfterCoast = false
        }
        lastHaltTime = eventTimeMs
        return false
    }

    fun onFingerUp() {
        fingerDown = false
    }
}

/** Enquanto o dedo está na tela o offset não acompanha o scroller. */
internal fun blitContentOffset(fingerDown: Boolean, offset: Int, scrollerY: Int): Int {
    return if (fingerDown) offset else scrollerY
}

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
    var onPinchCancelSelection: () -> Unit = {}
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
    private var prefetchStep: Int = 0
    private val coast = BlitCoast()
    private var selecting: Boolean = false
    private var lastDragPath: String? = null
    private var iconCloud: Bitmap? = null
    private var iconSynced: Bitmap? = null
    private var iconLocal: Bitmap? = null
    private var pinchDistance: Float = 0f
    private var pinchLocked: Boolean = false
    private var pinchActive: Boolean = false
    private var ignoreTapAfterPinch: Boolean = false
    private var openedSelectionThisGesture: Boolean = false
    private var downX: Float = 0f
    private var downY: Float = 0f
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val longPressTimeout = ViewConfiguration.getLongPressTimeout().toLong()
    private val longPressTask = Runnable { fireLongPress() }

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
    private val session = HashMap<String, Bitmap>()
    private var sessionByteCount: Int = 0
    private val sessionBudgetBytes: Int = blitSessionCacheBytes(
        (context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.largeMemoryClass ?: 192,
    )
    @Volatile private var released: Boolean = false
    private val decodeThread = HandlerThread("gallery-blit").apply { start() }
    private val decodeHandler = Handler(decodeThread.looper)
    private val badgeThread = HandlerThread(
        "gallery-badge",
        android.os.Process.THREAD_PRIORITY_BACKGROUND,
    ).apply { start() }
    private val badgeHandler = Handler(badgeThread.looper)
    private val badgeState = HashMap<String, CloudSyncState?>()
    private val badgeReady = HashSet<String>()
    private val badgeSignature = HashMap<String, String>()
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var decodeGeneration: Int = 0
    @Volatile private var badgeGeneration: Int = 0
    private val decodeRunnable = Runnable { enqueueVisible() }
    private val scrubEnd = Runnable { finishScroll() }

    private val gestures = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                haltCoast(e.eventTime)
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean {
                if (selecting || pinchLocked || ignoreTapAfterPinch || coast.ignoreTapAfterCoast) return true
                val path = hit(e.x, e.y) ?: return true
                if (selectionMode) onTogglePath(path) else onOpenPath(path)
                return true
            }

            override fun onLongPress(e: MotionEvent) {
                // O timer próprio é cancelado quando entra o segundo dedo.
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
                coast.onFlingStarted()
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
        prefetchStep = 0
        entries = next
        val live = HashSet<String>()
        for (entry in next) {
            val path = (entry as? GalleryGridEntry.Photo)?.item?.canonicalPath ?: continue
            live.add(path)
        }
        badgeReady.retainAll(live)
        badgeState.keys.retainAll(live)
        badgeSignature.keys.retainAll(live)
        rebuild()
        contentOffset = offset.coerceIn(0, maxScroll())
        updateFirstIndex()
        invalidate()
        scheduleDecode()
        scheduleBadgeResolve()
        if (!scrollActive && !pinchActive) trimSession()
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
        prefetchStep = 0
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

    fun bindIcons(cloud: Bitmap, synced: Bitmap, local: Bitmap) {
        if (cloud === iconCloud && synced === iconSynced && local === iconLocal) return
        iconCloud = cloud
        iconSynced = synced
        iconLocal = local
        invalidate()
    }

    /**
     * Para o embalo no lugar. O mesmo toque não abre a foto nem inicia seleção.
     * Vários despachos do mesmo ACTION_DOWN (Compose e a view) compartilham o tempo.
     */
    fun haltCoast(eventTimeMs: Long) {
        if (!coast.onFingerDown(eventTimeMs, !scroller.isFinished)) return
        scroller.forceFinished(true)
        invalidate()
    }

    fun scrollToEntry(index: Int) {
        scroller.forceFinished(true)
        coast.onFlingSettled()
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
        released = true
        decodeGeneration++
        badgeGeneration++
        mainHandler.removeCallbacks(decodeRunnable)
        mainHandler.removeCallbacks(longPressTask)
        decodeHandler.removeCallbacksAndMessages(null)
        badgeHandler.removeCallbacksAndMessages(null)
        decodeThread.quitSafely()
        badgeThread.quitSafely()
        synchronized(cacheLock) {
            for (bitmap in session.values) {
                if (!bitmap.isRecycled) bitmap.recycle()
            }
            session.clear()
            sessionByteCount = 0
        }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        val keep = firstVisible
        rebuild()
        if (slots.isNotEmpty()) {
            contentOffset = slots[keep.coerceIn(0, slots.lastIndex)].top.coerceIn(0, maxScroll())
        }
        updateFirstIndex()
        scheduleDecode()
        scheduleBadgeResolve()
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            parent?.requestDisallowInterceptTouchEvent(true)
            haltCoast(event.eventTime)
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                haltCoast(event.eventTime)
                beginSingleFinger(event)
            }
            MotionEvent.ACTION_POINTER_DOWN -> haltCoast(event.eventTime)
            MotionEvent.ACTION_MOVE -> {
                if (coast.flinging || !scroller.isFinished) haltCoast(event.eventTime)
                if (!pinchLocked &&
                    (abs(event.x - downX) > touchSlop || abs(event.y - downY) > touchSlop)
                ) {
                    mainHandler.removeCallbacks(longPressTask)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> coast.onFingerUp()
        }
        if (event.pointerCount >= 2 || event.actionMasked == MotionEvent.ACTION_POINTER_DOWN) {
            haltCoast(event.eventTime)
            lockPinch()
            pinch(event)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_POINTER_UP) {
            pinchDistance = 0f
            onMultiTouch(false)
        }
        gestures.onTouchEvent(event)
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            coast.onFingerUp()
            mainHandler.removeCallbacks(longPressTask)
            selecting = false
            lastDragPath = null
            pinchDistance = 0f
            pinchLocked = false
            if (pinchActive) {
                pinchActive = false
                scheduleDecode()
            }
            if (scroller.isFinished) finishScroll()
        }
        return true
    }

    override fun computeScroll() {
        if (coast.fingerDown) {
            if (!scroller.isFinished) scroller.forceFinished(true)
            coast.onFlingSettled()
            return
        }
        if (!scroller.computeScrollOffset()) {
            if (coast.flinging) {
                coast.onFlingSettled()
                finishScroll()
            }
            return
        }
        contentOffset = blitContentOffset(
            fingerDown = false,
            offset = contentOffset,
            scrollerY = scroller.currY,
        ).coerceIn(0, maxScroll())
        updateFirstIndex()
        postInvalidateOnAnimation()
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
        val bitmap = bitmapFor(item)
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
        val state = badgeForDraw(item) ?: return
        val icon = when (state) {
            CloudSyncState.LOCAL_ONLY -> iconLocal
            CloudSyncState.SYNCED -> iconSynced
            else -> iconCloud
        } ?: return
        val density = resources.displayMetrics.density
        val preferred = 22f * density
        val iconPx = preferred.coerceAtMost(slot.width * 0.34f).coerceAtLeast(12f)
        val pad = 6f * density
        val left = slot.left + slot.width - iconPx - pad
        val top = slot.top + pad
        dst.set(left.toInt(), top.toInt(), (left + iconPx).toInt(), (top + iconPx).toInt())
        canvas.drawBitmap(icon, null, dst, bitmapPaint)
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
        prefetchStep = 0
        decodeGeneration++
        badgeGeneration++
        decodeHandler.removeCallbacksAndMessages(null)
        badgeHandler.removeCallbacksAndMessages(null)
        GalleryGridScrollGate.isUserScrollingGrid = true
    }

    private fun finishScroll() {
        val idleAlready = !scrollActive && !coast.flinging
        if (!idleAlready) {
            scrollActive = false
            coast.onFlingSettled()
            GalleryGridScrollGate.isUserScrollingGrid = false
            onScrollEnd()
        }
        trimSession()
        scheduleDecode()
        scheduleBadgeResolve()
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

    private fun beginSingleFinger(event: MotionEvent) {
        pinchLocked = false
        openedSelectionThisGesture = false
        downX = event.x
        downY = event.y
        mainHandler.removeCallbacks(longPressTask)
        if (!coast.ignoreTapAfterCoast) {
            mainHandler.postDelayed(longPressTask, longPressTimeout)
        }
    }

    private fun fireLongPress() {
        if (pinchLocked || pinchActive) return
        val path = hit(downX, downY) ?: return
        selecting = true
        openedSelectionThisGesture = !selectionMode
        lastDragPath = path
        performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
        onLongPressPath(path)
    }

    private fun lockPinch() {
        val pointers = 2
        mainHandler.removeCallbacks(longPressTask)
        pinchLocked = true
        pinchActive = true
        prefetchStep = 0
        ignoreTapAfterPinch = true
        selecting = false
        lastDragPath = null
        decodeGeneration++
        decodeHandler.removeCallbacksAndMessages(null)
        if (pinchAbortsSelectionOpenedByThisGesture(pointers, openedSelectionThisGesture)) {
            openedSelectionThisGesture = false
            onPinchCancelSelection()
        }
    }

    private fun pinch(event: MotionEvent) {
        if (event.pointerCount < 2) return
        scroller.forceFinished(true)
        coast.onFlingSettled()
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

    private fun badgeForDraw(item: GalleryDisplayItem): CloudSyncState? {
        val path = item.canonicalPath
        if (path in badgeReady) return badgeState[path]
        return galleryGridCloudBadge(item)
    }

    private fun scheduleBadgeResolve() {
        if (scrollActive || slots.isEmpty() || width == 0) return
        val generation = ++badgeGeneration
        val first = blitFirstVisibleIndex(slots, contentOffset)
        val bottom = contentOffset + height
        var last = first
        while (last < slots.lastIndex && slots[last].top < bottom) last++
        val from = (first - 24).coerceAtLeast(0)
        val to = (last + 36).coerceAtMost(slots.lastIndex)
        val jobs = ArrayList<GalleryDisplayItem>()
        val signatures = ArrayList<String>()
        for (index in from..to) {
            val item = (entries.getOrNull(slots[index].entryIndex) as? GalleryGridEntry.Photo)?.item ?: continue
            val signature = badgeSignatureOf(item)
            if (badgeReady.contains(item.canonicalPath) && badgeSignature[item.canonicalPath] == signature) {
                continue
            }
            jobs.add(item)
            signatures.add(signature)
        }
        if (jobs.isEmpty()) return
        badgeHandler.post badgeResolve@{
            val resolved = ArrayList<Triple<String, String, CloudSyncState?>>(jobs.size)
            for (index in jobs.indices) {
                if (generation != badgeGeneration || GalleryGridScrollGate.isUserScrollingGrid) return@badgeResolve
                val item = jobs[index]
                val state = try {
                    mergeGalleryGridBadge(
                        galleryGridCloudBadge(item),
                        galleryCloudOverlayState(item, context),
                    )
                } catch (_: Exception) {
                    galleryGridCloudBadge(item)
                }
                resolved.add(Triple(item.canonicalPath, signatures[index], state))
            }
            mainHandler.post {
                if (generation != badgeGeneration) return@post
                for ((path, signature, state) in resolved) {
                    badgeSignature[path] = signature
                    badgeState[path] = state
                    badgeReady.add(path)
                }
                if (!scrollActive) invalidate()
            }
        }
    }

    private fun scheduleDecode() {
        mainHandler.removeCallbacks(decodeRunnable)
        mainHandler.postDelayed(decodeRunnable, 32)
    }

    private fun bitmapFor(item: GalleryDisplayItem): Bitmap? {
        val bitmap = session[item.canonicalPath] ?: return null
        if (bitmap.isRecycled) return null
        return bitmap
    }

    private fun enqueueVisible() {
        if (scrollActive || pinchActive || released || slots.isEmpty() || width == 0) return
        val generation = ++decodeGeneration
        val target = blitThumbTargetPx(cellSize.coerceAtLeast(cellMinPx))
        val estimate = target.toLong() * target.toLong() * 4L
        val jobs = ArrayList<DecodeJob>(BLIT_PREFETCH_WAVE)
        val first = blitFirstVisibleIndex(slots, contentOffset)
        var last = first
        val bottom = contentOffset + height
        while (last < slots.lastIndex && slots[last + 1].top < bottom) last++
        val anchor = (first + last) / 2
        var more = false
        var scans = 0
        while (jobs.size < BLIT_PREFETCH_WAVE && scans < 8) {
            scans++
            val startedAt = prefetchStep
            val scan = blitPrefetchScan(
                slotCount = slots.size,
                anchor = anchor,
                visibleFrom = first,
                visibleTo = last,
                startStep = startedAt,
                maxIndexes = BLIT_PREFETCH_WAVE,
            )
            if (scan.indexes.isEmpty()) {
                prefetchStep = scan.nextStep
                more = false
                break
            }
            var consumed = 0
            var budgetStop = false
            for (index in scan.indexes) {
                val visible = index in first..last
                val queuedBytes = sessionByteCount + jobs.size * estimate
                if (!visible && queuedBytes >= sessionBudgetBytes) {
                    budgetStop = true
                    break
                }
                offerSlot(index, target, jobs)
                consumed++
                if (jobs.size >= BLIT_PREFETCH_WAVE) break
            }
            prefetchStep = startedAt + consumed
            more = !budgetStop && (consumed < scan.indexes.size || scan.more)
            if (budgetStop || jobs.size >= BLIT_PREFETCH_WAVE || !more) break
        }
        if (jobs.isEmpty()) {
            if (more) scheduleDecode()
            return
        }
        val continueAfter = more
        decodeHandler.post {
            for (job in jobs) {
                if (released || generation != decodeGeneration || GalleryGridScrollGate.isUserScrollingGrid) {
                    return@post
                }
                val bitmap = decodeJob(job)
                    ?: job.fallbackFile?.let { file ->
                        decodeJob(DecodeJob(job.path, file, null))
                    }
                    ?: continue
                if (released) {
                    if (!bitmap.isRecycled) bitmap.recycle()
                    return@post
                }
                mainHandler.post {
                    if (released || bitmap.isRecycled) {
                        if (!bitmap.isRecycled) bitmap.recycle()
                    } else {
                        rememberThumb(job.path, bitmap)
                        if (!scrollActive && !pinchActive) invalidate()
                    }
                }
            }
            if (continueAfter) {
                mainHandler.post {
                    if (!released && generation == decodeGeneration && !scrollActive && !pinchActive) {
                        scheduleDecode()
                    }
                }
            }
        }
    }

    private fun offerSlot(index: Int, target: Int, jobs: ArrayList<DecodeJob>) {
        val slot = slots.getOrNull(index) ?: return
        val item = (entries.getOrNull(slot.entryIndex) as? GalleryGridEntry.Photo)?.item ?: return
        if (jobs.any { it.path == item.canonicalPath }) return
        val held = synchronized(cacheLock) { session[item.canonicalPath] }
        if (held != null && !held.isRecycled &&
            blitThumbCoversTarget(min(held.width, held.height), target)
        ) {
            return
        }
        val source = blitDecodeSource(item) ?: return
        val fallback = if (source.second != null) {
            (gridPaintModel(item, mediaCache) as? DiskThumb)?.file?.takeIf { it.isFile }
        } else {
            null
        }
        jobs.add(DecodeJob(item.canonicalPath, source.first, source.second, fallback))
    }

    /** Uma bitmap por foto. A anterior só sai quando a nova já está no lugar, na thread principal. */
    private fun rememberThumb(path: String, bitmap: Bitmap) {
        synchronized(cacheLock) {
            val previous = session.put(path, bitmap)
            if (previous != null && previous !== bitmap && !previous.isRecycled) {
                sessionByteCount = (sessionByteCount - previous.byteCount).coerceAtLeast(0)
                previous.recycle()
            }
            if (previous !== bitmap) sessionByteCount += bitmap.byteCount
        }
        trimSession()
    }

    /**
     * Só corre com o dedo parado. A janela visível fica. O que está longe, ou fora da lista, sai.
     * Se a própria janela passar do orçamento, ela permanece — a tela não fica cinza.
     */
    private fun trimSession() {
        if (scrollActive || pinchActive || released) return
        synchronized(cacheLock) {
            if (sessionByteCount <= sessionBudgetBytes || slots.isEmpty()) return
            val indexes = HashMap<String, Int>(slots.size)
            for (index in slots.indices) {
                val path = slots[index].path ?: continue
                if (path !in indexes) indexes[path] = index
            }
            val first = if (slots.isEmpty()) 0 else blitFirstVisibleIndex(slots, contentOffset)
            var last = first
            if (slots.isNotEmpty()) {
                val bottom = contentOffset + height
                while (last < slots.lastIndex && slots[last + 1].top < bottom) last++
            }
            val protectFrom = (first - BLIT_SESSION_PROTECT_SLOTS).coerceAtLeast(0)
            val protectTo = last + BLIT_SESSION_PROTECT_SLOTS
            val anchor = (first + last) / 2
            val orphans = session.keys.filter { it !in indexes }
            val order = orphans + blitThumbEvictOrder(indexes, anchor, protectFrom, protectTo)
            for (path in order) {
                if (sessionByteCount <= sessionBudgetBytes) return
                val bitmap = session.remove(path) ?: continue
                if (!bitmap.isRecycled) {
                    sessionByteCount = (sessionByteCount - bitmap.byteCount).coerceAtLeast(0)
                    bitmap.recycle()
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
            val edge = blitThumbTargetPx(cellSize.coerceAtLeast(cellMinPx))
            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSize(bounds.outWidth, bounds.outHeight, edge)
                inPreferredConfig = Bitmap.Config.ARGB_8888
            }
            val decoded = if (job.file != null) {
                BitmapFactory.decodeFile(job.file.absolutePath, options)
            } else {
                val uri = job.uri ?: return null
                context.contentResolver.openInputStream(uri)?.use { input ->
                    BitmapFactory.decodeStream(input, null, options)
                }
            } ?: return null
            if (!decoded.isMutable && decoded.config == Bitmap.Config.ARGB_8888) return decoded
            val immutable = decoded.copy(Bitmap.Config.ARGB_8888, false)
            if (immutable != null && immutable !== decoded) decoded.recycle()
            immutable ?: decoded
        }.getOrNull()
    }

    init {
        setWillNotDraw(false)
        isClickable = true
        gestures.setIsLongpressEnabled(false)
    }

    /** Foto local entra pelo arquivo do aparelho. Vídeo e nuvem ficam na miniatura já gravada. */
    private fun blitDecodeSource(item: GalleryDisplayItem): Pair<File?, Uri?>? {
        if (item.kind == MediaKind.IMAGE && item.isLocalPhotoOnDevice()) {
            item.localUri?.let { return null to it }
            item.cachedOriginal?.takeIf { it.isFile && it.length() > 0L }?.let { return it to null }
        }
        val model = gridPaintModel(item, mediaCache) ?: return null
        val file = (model as? DiskThumb)?.file?.takeIf { it.isFile }
        val uri = (model as? Uri)?.takeUnless { it.isVideoContentUri() }
        if (file == null && uri == null) return null
        return file to uri
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
    onPinchCancelSelection: () -> Unit,
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
    val localTint = MaterialTheme.colorScheme.onSurfaceVariant
    val cloudIcon = rememberBadgeIcon(Icons.Outlined.Cloud, OneDriveBlue)
    val syncedIcon = rememberBadgeIcon(Icons.Outlined.CloudDone, OneDriveBlue)
    val localIcon = rememberBadgeIcon(Icons.Outlined.Smartphone, localTint)
    val density = androidx.compose.ui.platform.LocalDensity.current
    val gapPx = with(density) { 4.dp.roundToPx() }
    val padStart = with(density) { 8.dp.roundToPx() }
    val padEnd = with(density) {
        (8.dp + if (dateTimeline) GalleryFastScrollRailReservedWidth else 0.dp).roundToPx()
    }
    val padTop = with(density) { GalleryStickyTimelineBarHeight.roundToPx() }
    val headerHeight = with(density) { 44.dp.roundToPx() }
    val yearHeader = with(density) { 56.dp.roundToPx() }
    Box(
        modifier.pointerInput(Unit) {
            awaitEachGesture {
                val down = awaitFirstDown(
                    requireUnconsumed = false,
                    pass = PointerEventPass.Initial,
                )
                viewRef[0]?.haltCoast(down.uptimeMillis)
            }
        },
    ) {
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
                view.bindIcons(cloudIcon, syncedIcon, localIcon)
                view.onOpenPath = onOpen
                view.onTogglePath = onToggle
                view.onLongPressPath = onLongPress
                view.onDragPath = onDragSelect
                view.onPinchCancelSelection = onPinchCancelSelection
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

@Composable
private fun rememberBadgeIcon(icon: ImageVector, tint: Color): Bitmap {
    val argb = tint.toArgb()
    return remember(icon, argb) { rasterizeBadgeIcon(icon, argb) }
}

/** Mesmo traço de [GalleryCloudBadge]: nuvem, nuvem com visto, ou celular. */
internal fun rasterizeBadgeIcon(icon: ImageVector, argb: Int): Bitmap {
    val sizePx = 128
    val bitmap = Bitmap.createBitmap(sizePx, sizePx, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    canvas.scale(sizePx / icon.viewportWidth, sizePx / icon.viewportHeight)
    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb }
    drawBadgeGroup(canvas, icon.root, paint)
    if (!bitmap.isMutable) return bitmap
    val frozen = bitmap.copy(Bitmap.Config.ARGB_8888, false) ?: return bitmap
    bitmap.recycle()
    return frozen
}

private fun drawBadgeGroup(canvas: Canvas, group: VectorGroup, paint: Paint) {
    canvas.save()
    canvas.translate(group.translationX + group.pivotX, group.translationY + group.pivotY)
    canvas.rotate(group.rotation)
    canvas.scale(group.scaleX, group.scaleY)
    canvas.translate(-group.pivotX, -group.pivotY)
    if (group.clipPathData.isNotEmpty()) {
        canvas.clipPath(group.clipPathData.toPath().asAndroidPath())
    }
    for (index in 0 until group.size) {
        when (val node = group[index]) {
            is VectorPath -> drawBadgePath(canvas, node, paint)
            is VectorGroup -> drawBadgeGroup(canvas, node, paint)
        }
    }
    canvas.restore()
}

private fun drawBadgePath(canvas: Canvas, path: VectorPath, paint: Paint) {
    if (path.pathData.isEmpty()) return
    val androidPath = path.pathData.toPath().asAndroidPath()
    androidPath.fillType = if (path.pathFillType == PathFillType.EvenOdd) {
        android.graphics.Path.FillType.EVEN_ODD
    } else {
        android.graphics.Path.FillType.WINDING
    }
    val base = paint.color
    if (path.fill != null && path.fillAlpha > 0f) {
        paint.style = Paint.Style.FILL
        paint.color = base
        paint.alpha = (path.fillAlpha * 255f).roundToInt().coerceIn(0, 255)
        canvas.drawPath(androidPath, paint)
    }
    if (path.stroke != null && path.strokeLineWidth > 0f && path.strokeAlpha > 0f) {
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = path.strokeLineWidth
        paint.strokeCap = when (path.strokeLineCap) {
            StrokeCap.Round -> Paint.Cap.ROUND
            StrokeCap.Square -> Paint.Cap.SQUARE
            else -> Paint.Cap.BUTT
        }
        paint.strokeJoin = when (path.strokeLineJoin) {
            StrokeJoin.Round -> Paint.Join.ROUND
            StrokeJoin.Bevel -> Paint.Join.BEVEL
            else -> Paint.Join.MITER
        }
        paint.strokeMiter = path.strokeLineMiter
        paint.color = base
        paint.alpha = (path.strokeAlpha * 255f).roundToInt().coerceIn(0, 255)
        canvas.drawPath(androidPath, paint)
    }
    paint.color = base
}

private fun badgeSignatureOf(item: GalleryDisplayItem): String {
    return item.cloudState.ordinal.toString() + '|' +
        item.oneDriveItemId + '|' +
        item.localUri + '|' +
        item.localMediaId + '|' +
        item.localReleasedForSpace + '|' +
        item.cachedOriginal?.path
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
