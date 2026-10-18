package com.onegallery.sync.ui.gallery

import android.content.Context
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import androidx.media3.ui.PlayerView
import kotlin.math.abs

/**
 * Envolve o [PlayerView] para pinça (dois dedos) e arraste com um dedo quando ampliado,
 * sem bloquear toques simples nos controles do player.
 */
class ZoomablePlayerLayout(context: Context) : FrameLayout(context) {

    val playerView: PlayerView = PlayerView(context)

    var onZoomActiveChanged: ((Boolean) -> Unit)? = null

    private var scale = 1f
    private var panX = 0f
    private var panY = 0f
    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean = true

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (!factor.isFinite() || factor == 0f) return false
                applyScale(scale * factor, detector.focusX, detector.focusY)
                return true
            }
        },
    )

    private val panDetector = GestureDetector(
        context,
        object : GestureDetector.SimpleOnGestureListener() {
            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
                if (scale <= 1.02f) return false
                panX -= distanceX
                panY -= distanceY
                clampPan()
                applyTransform()
                return true
            }
        },
    )

    init {
        addView(
            playerView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
    }

    private fun applyScale(newScale: Float, pivotX: Float, pivotY: Float) {
        scale = newScale.coerceIn(1f, 5f)
        playerView.pivotX = pivotX
        playerView.pivotY = pivotY
        if (scale <= 1.02f) {
            scale = 1f
            panX = 0f
            panY = 0f
        }
        clampPan()
        applyTransform()
        onZoomActiveChanged?.invoke(scale > 1.02f)
    }

    private fun clampPan() {
        if (scale <= 1f || width == 0 || height == 0) {
            panX = 0f
            panY = 0f
            return
        }
        val maxX = width * (scale - 1f) / 2f
        val maxY = height * (scale - 1f) / 2f
        panX = panX.coerceIn(-maxX, maxX)
        panY = panY.coerceIn(-maxY, maxY)
    }

    private fun applyTransform() {
        playerView.scaleX = scale
        playerView.scaleY = scale
        playerView.translationX = panX
        playerView.translationY = panY
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.pointerCount >= 2) return true
        if (scale > 1.02f && ev.actionMasked == MotionEvent.ACTION_MOVE) return true
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.pointerCount >= 2) {
            scaleDetector.onTouchEvent(event)
            return true
        }
        if (scale > 1.02f) {
            panDetector.onTouchEvent(event)
            return true
        }
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) {
            if (abs(scale - 1f) < 0.02f) {
                onZoomActiveChanged?.invoke(false)
            }
        }
        return false
    }

    fun resetZoom() {
        applyScale(1f, width / 2f, height / 2f)
        onZoomActiveChanged?.invoke(false)
    }
}
