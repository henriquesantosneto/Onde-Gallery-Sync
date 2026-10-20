package com.onegallery.sync.ui.gallery

import android.content.Context
import android.view.GestureDetector
import android.view.KeyEvent
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

    /** Volume físico: o Compose decide se deve desmutar. */
    var onVolumeKeyPressed: (() -> Unit)? = null

    private var scale = 1f
    private var panX = 0f
    private var panY = 0f

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                parent?.requestDisallowInterceptTouchEvent(true)
                onZoomActiveChanged?.invoke(true)
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val factor = detector.scaleFactor
                if (!factor.isFinite() || factor == 0f) return false
                applyPinch(factor, detector.focusX, detector.focusY)
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (scale <= 1.02f) {
                    resetTransform()
                }
            }
        },
    ).apply {
        isQuickScaleEnabled = false
    }

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
                parent?.requestDisallowInterceptTouchEvent(true)
                panX -= distanceX
                panY -= distanceY
                clampPan()
                applyTransform()
                return true
            }
        },
    )

    init {
        isFocusable = true
        isFocusableInTouchMode = true
        isMotionEventSplittingEnabled = false
        addView(
            playerView,
            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT),
        )
        playerView.isMotionEventSplittingEnabled = false
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN &&
            (event.keyCode == KeyEvent.KEYCODE_VOLUME_UP || event.keyCode == KeyEvent.KEYCODE_VOLUME_DOWN)
        ) {
            onVolumeKeyPressed?.invoke()
            return false
        }
        return super.dispatchKeyEvent(event)
    }

    private fun applyPinch(factor: Float, focusX: Float, focusY: Float) {
        val previous = scale
        val newScale = (previous * factor).coerceIn(1f, 5f)
        if (previous <= 0f) return
        val scaleFactor = newScale / previous
        panX = focusX - scaleFactor * (focusX - panX)
        panY = focusY - scaleFactor * (focusY - panY)
        scale = newScale
        if (scale <= 1.02f) {
            resetTransform()
            return
        }
        clampPan()
        applyTransform()
        onZoomActiveChanged?.invoke(true)
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
        playerView.pivotX = width / 2f
        playerView.pivotY = height / 2f
        playerView.scaleX = scale
        playerView.scaleY = scale
        playerView.translationX = panX
        playerView.translationY = panY
    }

    private fun resetTransform() {
        scale = 1f
        panX = 0f
        panY = 0f
        applyTransform()
        onZoomActiveChanged?.invoke(false)
    }

    private fun inPinchGesture(event: MotionEvent): Boolean {
        return scaleDetector.isInProgress ||
            event.pointerCount >= 2 ||
            scale > 1.02f
    }

    private fun handlePinchEnd(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_UP,
            MotionEvent.ACTION_CANCEL,
            -> {
                if (scale <= 1.02f) {
                    onZoomActiveChanged?.invoke(false)
                }
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.pointerCount - 1 < 2 && scale <= 1.02f && !scaleDetector.isInProgress) {
                    onZoomActiveChanged?.invoke(false)
                }
            }
        }
    }

    /**
     * Um único [ScaleGestureDetector.onTouchEvent] por [MotionEvent] — repetir no
     * intercept/onTouch quebra a pinça de forma intermitente.
     */
    private fun handleZoomTouch(event: MotionEvent): Boolean {
        scaleDetector.onTouchEvent(event)
        if (!inPinchGesture(event)) {
            if (event.actionMasked == MotionEvent.ACTION_UP ||
                event.actionMasked == MotionEvent.ACTION_CANCEL
            ) {
                if (abs(scale - 1f) < 0.02f) {
                    onZoomActiveChanged?.invoke(false)
                }
            }
            return false
        }
        parent?.requestDisallowInterceptTouchEvent(true)
        if (scale > 1.02f && event.pointerCount == 1) {
            panDetector.onTouchEvent(event)
        }
        handlePinchEnd(event)
        return true
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        if (handleZoomTouch(event)) {
            return true
        }
        parent?.requestDisallowInterceptTouchEvent(false)
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        if (ev.actionMasked == MotionEvent.ACTION_POINTER_DOWN && ev.pointerCount >= 2) {
            parent?.requestDisallowInterceptTouchEvent(true)
            return true
        }
        if (scaleDetector.isInProgress || ev.pointerCount >= 2) {
            return true
        }
        if (scale > 1.02f && ev.actionMasked == MotionEvent.ACTION_MOVE) {
            return true
        }
        return false
    }

    fun resetZoom() {
        resetTransform()
    }
}
