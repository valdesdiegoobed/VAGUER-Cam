package com.hazbu.xcam.ui

import android.content.Context
import android.graphics.Matrix
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import com.hazbu.xcam.data.Constants
import kotlin.math.max
import kotlin.math.min

/**
 * Preview surface used by VAGUER Cam. Drag to move and pinch to zoom.
 * All gesture values are sanitized before they are persisted or rendered so a
 * malformed gesture cannot poison the saved state and crash the next launch.
 */
class TransformableImageView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0,
) : AppCompatImageView(context, attrs, defStyleAttr) {

    var onTransformChanged: ((TransformState) -> Unit)? = null

    private var state = TransformState()
    private var lastX = 0f
    private var lastY = 0f
    private var dragging = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val rawFactor = detector.scaleFactor
                if (!rawFactor.isFinite() || rawFactor <= 0f) return false

                val factor = rawFactor.coerceIn(0.75f, 1.33f)
                state.scaleX = safe(state.scaleX * factor, 1f, 0.25f, 4f)
                state.scaleY = safe(state.scaleY * factor, 1f, 0.25f, 4f)
                rebuildMatrix()
                notifyChangedSafely()
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    fun setTransformState(newState: TransformState) {
        state = sanitize(newState)
        rebuildMatrix()
    }

    fun getTransformState(): TransformState = state.copy()

    fun setScaleFactors(x: Float, y: Float) {
        state.scaleX = safe(x, 1f, 0.25f, 4f)
        state.scaleY = safe(y, 1f, 0.25f, 4f)
        rebuildMatrix()
        notifyChangedSafely()
    }

    fun rotateBy(degrees: Int) {
        state.rotation = ((state.rotation + degrees) % 360 + 360) % 360
        rebuildMatrix()
        notifyChangedSafely()
    }

    fun setMirrored(mirrored: Boolean) {
        state.mirrored = mirrored
        rebuildMatrix()
        notifyChangedSafely()
    }

    fun setFitMode(mode: String) {
        state.fitMode = when (mode) {
            Constants.FIT_MODE_FILL -> Constants.FIT_MODE_FILL
            Constants.FIT_MODE_STRETCH -> Constants.FIT_MODE_STRETCH
            else -> Constants.FIT_MODE_FIT
        }
        rebuildMatrix()
        notifyChangedSafely()
    }

    fun centerContent() {
        state.offsetX = 0f
        state.offsetY = 0f
        rebuildMatrix()
        notifyChangedSafely()
    }

    fun resetTransform() {
        val keepBrightness = safe(state.brightness, 0f, -1f, 1f)
        val keepContrast = safe(state.contrast, 0f, -1f, 1f)
        val keepSaturation = safe(state.saturation, 0f, -100f, 100f)
        val keepSharpness = safe(state.sharpness, 0f, 0f, 1f)
        state = TransformState(
            brightness = keepBrightness,
            contrast = keepContrast,
            saturation = keepSaturation,
            sharpness = keepSharpness,
        )
        rebuildMatrix()
        notifyChangedSafely()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        rebuildMatrix()
    }

    override fun setImageDrawable(drawable: android.graphics.drawable.Drawable?) {
        super.setImageDrawable(drawable)
        post { rebuildMatrix() }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            parent?.requestDisallowInterceptTouchEvent(true)
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    dragging = true
                }

                MotionEvent.ACTION_MOVE -> {
                    if (dragging && !scaleDetector.isInProgress && width > 0 && height > 0) {
                        val dx = event.x - lastX
                        val dy = event.y - lastY
                        if (dx.isFinite() && dy.isFinite()) {
                            state.offsetX = safe(
                                state.offsetX + dx / (width / 2f),
                                0f,
                                -2f,
                                2f,
                            )
                            state.offsetY = safe(
                                state.offsetY + dy / (height / 2f),
                                0f,
                                -2f,
                                2f,
                            )
                            lastX = event.x
                            lastY = event.y
                            rebuildMatrix()
                            notifyChangedSafely()
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    performClick()
                }
            }
        } catch (_: Throwable) {
            // A preview gesture must never take down the whole editor. Reset
            // only the active gesture and keep the last valid transform.
            dragging = false
            parent?.requestDisallowInterceptTouchEvent(false)
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun rebuildMatrix() {
        val d = drawable ?: return
        if (width <= 0 || height <= 0) return

        state = sanitize(state)

        val dw = max(1, d.intrinsicWidth).toFloat()
        val dh = max(1, d.intrinsicHeight).toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()
        if (!dw.isFinite() || !dh.isFinite() || !vw.isFinite() || !vh.isFinite()) return

        val fit = min(vw / dw, vh / dh)
        val fill = max(vw / dw, vh / dh)

        val baseX: Float
        val baseY: Float
        when (state.fitMode) {
            Constants.FIT_MODE_FILL -> {
                baseX = fill
                baseY = fill
            }

            Constants.FIT_MODE_STRETCH -> {
                baseX = vw / dw
                baseY = vh / dh
            }

            else -> {
                baseX = fit
                baseY = fit
            }
        }

        val mirrorSign = if (state.mirrored) -1f else 1f
        val matrix = Matrix()
        matrix.postTranslate(-dw / 2f, -dh / 2f)
        matrix.postScale(
            baseX * state.scaleX * mirrorSign,
            baseY * state.scaleY,
        )
        matrix.postRotate(state.rotation.toFloat())
        matrix.postTranslate(
            vw / 2f + state.offsetX * vw / 2f,
            vh / 2f + state.offsetY * vh / 2f,
        )
        imageMatrix = matrix
    }

    private fun notifyChangedSafely() {
        val snapshot = state.copy()
        try {
            onTransformChanged?.invoke(snapshot)
        } catch (_: Throwable) {
            // Keep the preview alive even if an external UI widget rejects an
            // intermediate gesture value. The next stable value can continue.
        }
    }

    private fun sanitize(input: TransformState): TransformState = input.copy(
        scaleX = safe(input.scaleX, 1f, 0.25f, 4f),
        scaleY = safe(input.scaleY, 1f, 0.25f, 4f),
        offsetX = safe(input.offsetX, 0f, -2f, 2f),
        offsetY = safe(input.offsetY, 0f, -2f, 2f),
        rotation = ((input.rotation % 360) + 360) % 360,
        fitMode = when (input.fitMode) {
            Constants.FIT_MODE_FILL -> Constants.FIT_MODE_FILL
            Constants.FIT_MODE_STRETCH -> Constants.FIT_MODE_STRETCH
            else -> Constants.FIT_MODE_FIT
        },
        brightness = safe(input.brightness, 0f, -1f, 1f),
        contrast = safe(input.contrast, 0f, -1f, 1f),
        saturation = safe(input.saturation, 0f, -100f, 100f),
        sharpness = safe(input.sharpness, 0f, 0f, 1f),
    )

    private fun safe(value: Float, fallback: Float, min: Float, max: Float): Float {
        return if (value.isFinite()) value.coerceIn(min, max) else fallback
    }
}
