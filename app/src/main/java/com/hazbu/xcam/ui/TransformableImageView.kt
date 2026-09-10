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
 * Preview surface used by VAGUER Cam. It behaves like a lightweight desktop
 * "Transform" tool: drag to move, pinch to zoom, and use the external controls
 * to stretch, rotate, mirror, fit, fill or reset the media.
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
                val factor = detector.scaleFactor.coerceIn(0.75f, 1.33f)
                state.scaleX = (state.scaleX * factor).coerceIn(0.25f, 4f)
                state.scaleY = (state.scaleY * factor).coerceIn(0.25f, 4f)
                rebuildMatrix()
                notifyChanged()
                return true
            }
        },
    )

    init {
        scaleType = ScaleType.MATRIX
        isClickable = true
    }

    fun setTransformState(newState: TransformState) {
        state = newState.copy()
        rebuildMatrix()
    }

    fun getTransformState(): TransformState = state.copy()

    fun setScaleFactors(x: Float, y: Float) {
        state.scaleX = x.coerceIn(0.25f, 4f)
        state.scaleY = y.coerceIn(0.25f, 4f)
        rebuildMatrix()
        notifyChanged()
    }

    fun rotateBy(degrees: Int) {
        state.rotation = ((state.rotation + degrees) % 360 + 360) % 360
        rebuildMatrix()
        notifyChanged()
    }

    fun setMirrored(mirrored: Boolean) {
        state.mirrored = mirrored
        rebuildMatrix()
        notifyChanged()
    }

    fun setFitMode(mode: String) {
        state.fitMode = when (mode) {
            Constants.FIT_MODE_FILL -> Constants.FIT_MODE_FILL
            Constants.FIT_MODE_STRETCH -> Constants.FIT_MODE_STRETCH
            else -> Constants.FIT_MODE_FIT
        }
        rebuildMatrix()
        notifyChanged()
    }

    fun centerContent() {
        state.offsetX = 0f
        state.offsetY = 0f
        rebuildMatrix()
        notifyChanged()
    }

    fun resetTransform() {
        val keepBrightness = state.brightness
        val keepContrast = state.contrast
        val keepSaturation = state.saturation
        val keepSharpness = state.sharpness
        state = TransformState(
            brightness = keepBrightness,
            contrast = keepContrast,
            saturation = keepSaturation,
            sharpness = keepSharpness,
        )
        rebuildMatrix()
        notifyChanged()
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
                    state.offsetX = (state.offsetX + dx / (width / 2f)).coerceIn(-2f, 2f)
                    state.offsetY = (state.offsetY + dy / (height / 2f)).coerceIn(-2f, 2f)
                    lastX = event.x
                    lastY = event.y
                    rebuildMatrix()
                    notifyChanged()
                }
            }

            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                dragging = false
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
            }
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

        val dw = max(1, d.intrinsicWidth).toFloat()
        val dh = max(1, d.intrinsicHeight).toFloat()
        val vw = width.toFloat()
        val vh = height.toFloat()

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

    private fun notifyChanged() {
        onTransformChanged?.invoke(state.copy())
    }
}
