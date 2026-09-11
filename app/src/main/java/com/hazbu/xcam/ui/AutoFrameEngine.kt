package com.hazbu.xcam.ui

import android.graphics.Bitmap
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import com.google.mlkit.vision.face.FaceLandmark
import kotlin.math.min
import kotlin.math.round

/**
 * Face-aware framing for the VAGUER reference look.
 *
 * The detector never changes facial geometry. It only calculates a uniform
 * zoom and X/Y translation so different source photos land in a repeatable
 * framing. The reference constants come from the approved VAGUER framing
 * video and are intentionally isolated here so they can be fine-tuned later.
 */
object AutoFrameEngine {

    const val REFERENCE_BRIGHTNESS = 0.11f
    const val REFERENCE_CONTRAST = 0.59f
    const val REFERENCE_SATURATION = 30f
    const val REFERENCE_SHARPNESS = 0.97f
    const val REFERENCE_FALLBACK_SCALE = 1.56f

    // Initial framing pattern extracted from the approved reference.
    private const val TARGET_FACE_WIDTH_FRACTION = 0.52f
    private const val TARGET_FACE_CENTER_X_FRACTION = 0.50f
    private const val TARGET_EYE_Y_FRACTION = 0.415f
    private const val TARGET_FACE_CENTER_Y_FRACTION = 0.48f

    data class FrameResult(
        val scale: Float,
        val offsetX: Float,
        val offsetY: Float,
        val faceCount: Int,
        val usedEyeLine: Boolean,
    )

    fun detect(
        bitmap: Bitmap,
        viewWidth: Int,
        viewHeight: Int,
        onSuccess: (FrameResult?) -> Unit,
        onFailure: (Throwable) -> Unit,
    ) {
        if (bitmap.width <= 0 || bitmap.height <= 0 || viewWidth <= 0 || viewHeight <= 0) {
            onFailure(IllegalArgumentException("Tamaño de imagen o vista inválido"))
            return
        }

        val options = FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
            .setMinFaceSize(0.12f)
            .build()

        val detector = FaceDetection.getClient(options)
        detector.process(InputImage.fromBitmap(bitmap, 0))
            .addOnSuccessListener { faces ->
                val face = faces.maxByOrNull {
                    it.boundingBox.width().toLong() * it.boundingBox.height().toLong()
                }
                onSuccess(
                    face?.let {
                        calculate(
                            bitmap = bitmap,
                            face = it,
                            faceCount = faces.size,
                            viewWidth = viewWidth,
                            viewHeight = viewHeight,
                        )
                    }
                )
            }
            .addOnFailureListener { onFailure(it) }
            .addOnCompleteListener { detector.close() }
    }

    private fun calculate(
        bitmap: Bitmap,
        face: Face,
        faceCount: Int,
        viewWidth: Int,
        viewHeight: Int,
    ): FrameResult {
        val vw = viewWidth.toFloat()
        val vh = viewHeight.toFloat()
        val bw = bitmap.width.toFloat()
        val bh = bitmap.height.toFloat()

        // VAGUER editor uses FIT_CENTER as the neutral geometry.
        val baseScale = min(vw / bw, vh / bh)
        val drawnWidth = bw * baseScale
        val drawnHeight = bh * baseScale
        val originX = (vw - drawnWidth) / 2f
        val originY = (vh - drawnHeight) / 2f

        val box = face.boundingBox
        val faceWidthOnView = box.width().coerceAtLeast(1) * baseScale
        val targetFaceWidth = vw * TARGET_FACE_WIDTH_FRACTION
        val zoom = (targetFaceWidth / faceWidthOnView).coerceIn(0.25f, 4f)

        val faceCenterXInBitmap = (box.left + box.right) / 2f
        val faceCenterYInBitmap = (box.top + box.bottom) / 2f

        val leftEye = face.getLandmark(FaceLandmark.LEFT_EYE)?.position
        val rightEye = face.getLandmark(FaceLandmark.RIGHT_EYE)?.position
        val hasBothEyes = leftEye != null && rightEye != null

        val anchorXBitmap = faceCenterXInBitmap
        val anchorYBitmap = if (hasBothEyes) {
            (leftEye!!.y + rightEye!!.y) / 2f
        } else {
            faceCenterYInBitmap
        }

        val anchorX = originX + anchorXBitmap * baseScale
        val anchorY = originY + anchorYBitmap * baseScale

        val pivotX = vw / 2f
        val pivotY = vh / 2f

        // Android view scaling occurs around the center pivot before translation.
        val scaledAnchorX = pivotX + zoom * (anchorX - pivotX)
        val scaledAnchorY = pivotY + zoom * (anchorY - pivotY)

        val targetX = vw * TARGET_FACE_CENTER_X_FRACTION
        val targetY = vh * if (hasBothEyes) {
            TARGET_EYE_Y_FRACTION
        } else {
            TARGET_FACE_CENTER_Y_FRACTION
        }

        val translationX = targetX - scaledAnchorX
        val translationY = targetY - scaledAnchorY

        return FrameResult(
            scale = q(zoom),
            offsetX = q((translationX / (vw / 2f)).coerceIn(-2f, 2f)),
            offsetY = q((translationY / (vh / 2f)).coerceIn(-2f, 2f)),
            faceCount = faceCount,
            usedEyeLine = hasBothEyes,
        )
    }

    private fun q(value: Float): Float = round(value * 100f) / 100f
}
