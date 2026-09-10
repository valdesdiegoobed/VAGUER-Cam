package com.hazbu.xcam.ui

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import androidx.appcompat.widget.AppCompatImageView
import com.hazbu.xcam.data.Constants

/**
 * Stable editor preview for VAGUER Cam.
 *
 * The first editor implementation rebuilt ImageView matrices on every touch
 * event and immediately synchronized Material sliders. On some devices that
 * made pinch gestures race UI validation/layout work and could close the app.
 *
 * This version deliberately follows the simpler approach used by the original
 * xCam preview: Android's own ImageView scale/rotation/translation properties
 * do the rendering, while gestures only notify the activity once the gesture
 * ends. That removes per-frame SharedPreferences writes and slider updates.
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
    private var gestureDirty = false

    private val scaleDetector = ScaleGestureDetector(
        context,
        object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                gestureDirty = true
                return true
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                val raw = detector.scaleFactor
                if (!raw.isFinite() || raw <= 0f) return false

                val factor = raw.coerceIn(0.80f, 1.25f)
                state.scaleX = safe(state.scaleX * factor, 1f, 0.25f, 4f)
                state.scaleY = safe(state.scaleY * factor, 1f, 0.25f, 4f)
                applyTransform()
                gestureDirty = true
                return true
            }
        },
    )

    init {
        isClickable = true
        clipToOutline = true
        applyScaleType()
    }

    fun setTransformState(newState: TransformState) {
        state = sanitize(newState)
        applyTransform()
    }

    fun getTransformState(): TransformState = state.copy()

    fun setScaleFactors(x: Float, y: Float) {
        state.scaleX = safe(x, 1f, 0.25f, 4f)
        state.scaleY = safe(y, 1f, 0.25f, 4f)
        applyTransform()
        notifyChangedSafely()
    }

    fun rotateBy(degrees: Int) {
        state.rotation = normalizeRotation(state.rotation + degrees)
        applyTransform()
        notifyChangedSafely()
    }

    fun setMirrored(mirrored: Boolean) {
        state.mirrored = mirrored
        applyTransform()
        notifyChangedSafely()
    }

    fun setFitMode(mode: String) {
        state.fitMode = normalizeFitMode(mode)
        applyTransform()
        notifyChangedSafely()
    }

    fun centerContent() {
        state.offsetX = 0f
        state.offsetY = 0f
        applyTransform()
        notifyChangedSafely()
    }

    fun resetTransform() {
        val brightness = state.brightness
        val contrast = state.contrast
        val saturation = state.saturation
        val sharpness = state.sharpness
        state = TransformState(
            brightness = safe(brightness, 0f, -1f, 1f),
            contrast = safe(contrast, 0f, -1f, 1f),
            saturation = safe(saturation, 0f, -100f, 100f),
            sharpness = safe(sharpness, 0f, 0f, 1f),
        )
        applyTransform()
        notifyChangedSafely()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        applyTransform()
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        try {
            scaleDetector.onTouchEvent(event)

            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    lastX = event.x
                    lastY = event.y
                    dragging = true
                    gestureDirty = false
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_POINTER_DOWN -> {
                    parent?.requestDisallowInterceptTouchEvent(true)
                }

                MotionEvent.ACTION_MOVE -> {
                    if (
                        dragging &&
                        event.pointerCount == 1 &&
                        !scaleDetector.isInProgress &&
                        width > 0 && height > 0
                    ) {
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
                            applyTransform()
                            gestureDirty = true
                        }
                    }
                }

                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false
                    parent?.requestDisallowInterceptTouchEvent(false)
                    if (gestureDirty) {
                        // Snap to 0.01 only after the gesture ends. The visual
                        // gesture stays smooth while saved values stay clean.
                        state.scaleX = quantize(state.scaleX)
                        state.scaleY = quantize(state.scaleY)
                        state.offsetX = quantize(state.offsetX)
                        state.offsetY = quantize(state.offsetY)
                        applyTransform()
                        notifyChangedSafely()
                    }
                    gestureDirty = false
                    performClick()
                }
            }
        } catch (_: Throwable) {
            dragging = false
            gestureDirty = false
            parent?.requestDisallowInterceptTouchEvent(false)
            // Never allow a malformed touch sequence to take down the editor.
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun applyTransform() {
        state = sanitize(state)
        applyScaleType()

        // Use platform view transforms instead of rebuilding an ImageView
        // Matrix. This is intentionally close to original xCam behavior.
        pivotX = width / 2f
        pivotY = height / 2f
        scaleX = state.scaleX * if (state.mirrored) -1f else 1f
        scaleY = state.scaleY
        rotation = state.rotation.toFloat()
        translationX = if (width > 0) state.offsetX * width / 2f else 0f
        translationY = if (height > 0) state.offsetY * height / 2f else 0f
    }

    private fun applyScaleType() {
        scaleType = when (state.fitMode) {
            Constants.FIT_MODE_FILL -> ScaleType.CENTER_CROP
            Constants.FIT_MODE_STRETCH -> ScaleType.FIT_XY
            else -> ScaleType.FIT_CENTER
        }
    }

    private fun notifyChangedSafely() {
        runCatching { onTransformChanged?.invoke(state.copy()) }
    }

    private fun sanitize(input: TransformState): TransformState = input.copy(
        scaleX = safe(input.scaleX, 1f, 0.25f, 4f),
        scaleY = safe(input.scaleY, 1f, 0.25f, 4f),
        offsetX = safe(input.offsetX, 0f, -2f, 2f),
        offsetY = safe(input.offsetY, 0f, -2f, 2f),
        rotation = normalizeRotation(input.rotation),
        fitMode = normalizeFitMode(input.fitMode),
        brightness = safe(input.brightness, 0f, -1f, 1f),
        contrast = safe(input.contrast, 0f, -1f, 1f),
        saturation = safe(input.saturation, 0f, -100f, 100f),
        sharpness = safe(input.sharpness, 0f, 0f, 1f),
    )

    private fun normalizeFitMode(mode: String): String = when (mode) {
        Constants.FIT_MODE_FILL -> Constants.FIT_MODE_FILL
        Constants.FIT_MODE_STRETCH -> Constants.FIT_MODE_STRETCH
        else -> Constants.FIT_MODE_FIT
    }

    private fun normalizeRotation(value: Int): Int = ((value % 360) + 360) % 360

    private fun safe(value: Float, fallback: Float, min: Float, max: Float): Float {
        return if (value.isFinite()) value.coerceIn(min, max) else fallback
    }

    private fun quantize(value: Float): Float = (value * 100f).toInt() / 100f
}
